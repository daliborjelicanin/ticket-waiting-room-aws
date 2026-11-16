# Every runtime role of the system (D12, retro §4). Nothing is implied: no managed policies,
# no console defaults, every role under the permissions boundary.
#
# THE RULE THAT DECIDES WHICH ROLE GETS A PERMISSION (it recurred in five Phase 1 slices):
#   - A workload's role is ITS identity when it calls AWS: an ECS task role, a Lambda role.
#     An event source mapping is the exception that polls the queue with the function's role.
#   - A service's role is THAT SERVICE's identity when it calls into ours: the scheduler
#     invoking a function, API Gateway writing the connection table, Step Functions sending
#     to a queue, ECS pulling an image (the execution role, not the task role).
#
# How policies are written (D12): aws_iam_policy_document only; the role's own policy inline;
# Resource "*" only where the API offers nothing narrower, with a comment. KMS use is granted
# here, on the one key, with kms:ViaService, so a role can use the key only through the
# service it was meant for. Trust conditions (aws:SourceAccount / aws:SourceArn) are written
# out where the service documents them; an unsupported one fails closed as an opaque error.

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region
  arn        = "arn:${local.partition}" # arn:aws

  log_group_arn = { for k, name in merge(
    { for s, n in var.log_groups.ecs : "ecs/${s}" => n },
    { for f, n in var.log_groups.lambda : "lambda/${f}" => n },
    { for a, n in var.log_groups.apigw : "apigw/${a}" => n },
    { metrics = var.log_groups.metrics, ecs_exec = var.log_groups.ecs_exec, saga = var.log_groups.saga },
  ) : k => "${local.arn}:logs:${local.region}:${local.account_id}:log-group:${name}" }

  # Every role this module creates, as three flat maps keyed by role: its policy, its trust
  # principal, and the aws:SourceArn its trust requires (null = no source conditions).
  # Flat string maps rather than one map of objects: IntelliJ's HCL plugin loses the element
  # type of a merge() result, so each.value.<attr> on it shows as unresolved (infra/CLAUDE.md).
  ecs_source_arn = "${local.arn}:ecs:${local.region}:${local.account_id}:*"

  role_policy = merge(
    { for s in keys(local.ecs_services) : "${s}-task" => data.aws_iam_policy_document.task[s].json },
    { for s in keys(local.ecs_services) : "${s}-execution" => data.aws_iam_policy_document.execution[s].json },
    { for f, doc in local.lambda_policies : "${f}-lambda" => doc },
    { for k, r in local.integration_roles : k => r.policy },
  )

  role_principal = merge(
    { for s in keys(local.ecs_services) : "${s}-task" => "ecs-tasks.amazonaws.com" },
    { for s in keys(local.ecs_services) : "${s}-execution" => "ecs-tasks.amazonaws.com" },
    { for f in keys(local.lambda_policies) : "${f}-lambda" => "lambda.amazonaws.com" },
    { for k, r in local.integration_roles : k => r.principal },
  )

  role_source_arn = merge(
    { for s in keys(local.ecs_services) : "${s}-task" => local.ecs_source_arn },
    { for s in keys(local.ecs_services) : "${s}-execution" => local.ecs_source_arn },
    { for f in keys(local.lambda_policies) : "${f}-lambda" => null },
    { for k, r in local.integration_roles : k => r.source_arn },
  )
}

data "aws_iam_policy_document" "trust" {
  for_each = local.role_policy

  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = [local.role_principal[each.key]]
    }

    dynamic "condition" {
      for_each = local.role_source_arn[each.key] == null ? [] : [1]

      content {
        test     = "StringEquals"
        variable = "aws:SourceAccount"
        values   = [local.account_id]
      }
    }

    dynamic "condition" {
      for_each = local.role_source_arn[each.key] == null ? [] : [1]

      content {
        test     = "ArnLike"
        variable = "aws:SourceArn"
        values   = [local.role_source_arn[each.key]]
      }
    }
  }
}

resource "aws_iam_role" "this" {
  for_each = local.role_policy

  name                 = "${var.name_prefix}-${each.key}"
  assume_role_policy   = data.aws_iam_policy_document.trust[each.key].json
  permissions_boundary = var.permissions_boundary_arn
}

resource "aws_iam_role_policy" "this" {
  for_each = local.role_policy

  name   = "permissions"
  role   = aws_iam_role.this[each.key].id
  policy = local.role_policy[each.key]
}

# --- shared statements (composed into roles with source_policy_documents) -----------------

# Traces go to X-Ray through the collector sidecar or Lambda's own tracing. The X-Ray API
# has no resource-level permissions, so "*" (D12).
data "aws_iam_policy_document" "xray" {
  statement {
    sid       = "XRayHasNoResourceLevelPermissions"
    actions   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords"]
    resources = ["*"]
  }
}

# The OTLP collector sidecar (observability-starter): traces to X-Ray, metrics as EMF log
# events into the one metrics log group, never PutMetricData from the service.
data "aws_iam_policy_document" "collector" {
  source_policy_documents = [data.aws_iam_policy_document.xray.json]

  statement {
    sid       = "MetricsAsEmfLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
    resources = [local.log_group_arn["metrics"], "${local.log_group_arn["metrics"]}:*"]
  }
}

# ECS Exec, the bastion that replaced Phase 1's $MYIP rule (D9), audited: every session is
# logged (D12). The ssmmessages channel API has no resources to scope to, hence "*".
data "aws_iam_policy_document" "ecs_exec" {
  statement {
    sid = "ExecChannelsHaveNoResources"
    actions = [
      "ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "FindTheExecLogGroup"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"] # a List-style API: it takes no resource
  }

  statement {
    sid       = "AuditEverySession"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${local.log_group_arn["ecs_exec"]}:*"]
  }
}
