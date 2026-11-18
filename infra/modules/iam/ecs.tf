# ECS: one task role and one execution role per service (D12). The execution role is what
# ECS uses to start the task: pull the image, write the log stream, resolve `secrets`
# (valueFrom). A shared one would let checkout's task definition pull ticketing's password.

locals {
  # repositories: ECR repos the task runs (keys of var.repository_arns).
  # secrets:      per-service DB secrets it receives (keys of var.db.service_secret_names).
  # rds_master:   the RDS-managed master secret (only the migration task, D9, D10).
  # collector:    runs the OTLP collector sidecar (every long-running service).
  ecs_services = {
    waiting-room   = { repositories = ["waiting-room-service"], secrets = [], rds_master = false, collector = true }
    seat-inventory = { repositories = ["seat-inventory-service"], secrets = [], rds_master = false, collector = true }
    checkout       = { repositories = ["checkout-payment-service"], secrets = ["checkout"], rds_master = false, collector = true }
    issuance       = { repositories = ["ticket-issuance-service"], secrets = ["ticketing"], rds_master = false, collector = true }
    notification   = { repositories = ["notification-service"], secrets = [], rds_master = false, collector = true }
    # Runs each service image with the migrate profile; sets the service roles' passwords from
    # their secrets, so it reads both and the master (D10, D13).
    migrate = {
      repositories = ["checkout-payment-service", "ticket-issuance-service"]
      secrets      = ["checkout", "ticketing"]
      rds_master   = true
      collector    = false
    }
  }

  secret_arn_pattern = {
    for k, name in var.db.service_secret_names :
    # Secrets Manager appends "-" and six random characters to every secret ARN.
    k => "${local.arn}:secretsmanager:${local.region}:${local.account_id}:secret:${name}-??????"
  }

  kms_via = {
    for svc in ["sqs", "sns", "s3", "secretsmanager"] : svc => "${svc}.${local.region}.amazonaws.com"
  }
}

# --- what each service itself calls ------------------------------------------------------

data "aws_iam_policy_document" "waiting_room" {
  statement {
    sid       = "AnnounceAdmissions"
    actions   = ["sqs:SendMessage"]
    resources = [var.queue_arns["queue-admissions"]]
  }

  statement {
    sid       = "EncryptForTheQueue"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["sqs"]]
    }
  }
}

data "aws_iam_policy_document" "seat_inventory" {
  # The conditional writes are the seat lock; no Scan, no DeleteItem, no BatchWrite.
  statement {
    sid       = "SeatTable"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query", "dynamodb:UpdateItem"]
    resources = [var.seat_table_arn]
  }
}

data "aws_iam_policy_document" "issuance" {
  statement {
    sid       = "ConsumeIssuance"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [var.queue_arns["ticket-issuance"]]
  }

  statement {
    sid       = "RequestQrGeneration"
    actions   = ["sqs:SendMessage"]
    resources = [var.queue_arns["qr-generation"]]
  }

  statement {
    sid       = "PresignQrDownloads"
    actions   = ["s3:GetObject"]
    resources = ["${var.qr_bucket_arn}/tickets/*"]
  }

  # Without it a missing object is a 403, not a 404, and the QR endpoint 500s (retro §3).
  statement {
    sid       = "TellMissingFromForbidden"
    actions   = ["s3:ListBucket"]
    resources = [var.qr_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["tickets/*"]
    }
  }

  statement {
    sid       = "QueuesOnTheKey"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["sqs"]]
    }
  }

  statement {
    sid       = "ReadQrImagesOnTheKey"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["s3"]]
    }
  }
}

data "aws_iam_policy_document" "notification" {
  statement {
    sid       = "ConsumeNotifications"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [var.queue_arns["purchase-confirmed-notifications"]]
  }

  statement {
    sid       = "ReadTheQueueOnTheKey"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["sqs"]]
    }
  }

  # Sending is authorised against every identity involved (sender and, in the sandbox, the
  # recipient), so the resource is any identity; the condition pins the sender.
  statement {
    sid       = "SendAsTheProjectSender"
    actions   = ["ses:SendEmail"]
    resources = ["${local.arn}:ses:${local.region}:${local.account_id}:identity/*"]

    condition {
      test     = "StringEquals"
      variable = "ses:FromAddress"
      values   = [var.ses_from_address]
    }
  }
}

locals {
  task_own_policies = {
    waiting-room   = [data.aws_iam_policy_document.waiting_room.json]
    seat-inventory = [data.aws_iam_policy_document.seat_inventory.json]
    checkout       = [] # its AWS-facing work is Postgres (password) and HTTP to seat-inventory
    issuance       = [data.aws_iam_policy_document.issuance.json]
    notification   = [data.aws_iam_policy_document.notification.json]
    migrate        = [] # Flyway over a password; nothing in AWS
  }
}

# Task role: the service's own calls, the collector sidecar, ECS Exec.
data "aws_iam_policy_document" "task" {
  for_each = local.ecs_services

  source_policy_documents = concat(
    local.task_own_policies[each.key],
    each.value.collector ? [data.aws_iam_policy_document.collector.json] : [],
    [data.aws_iam_policy_document.ecs_exec.json],
  )
}

# Execution role: image, log stream, collector config, secrets. Nothing the service calls.
data "aws_iam_policy_document" "execution" {
  for_each = local.ecs_services

  statement {
    sid       = "EcrLoginHasNoResource"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "PullOwnImage"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [for r in each.value.repositories : var.repository_arns[r]]
  }

  statement {
    sid       = "WriteOwnLogGroup"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${local.log_group_arn["ecs/${each.key}"]}:*"]
  }

  # The collector image comes through the registry's pull-through cache, so its layers skip
  # the NAT (modules/ecr). Importing is what a first pull of a not-yet-cached tag does.
  dynamic "statement" {
    for_each = each.value.collector ? [1] : []

    content {
      sid = "PullCollectorThroughTheCache"
      actions = [
        "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
        "ecr:BatchImportUpstreamImage",
      ]
      resources = [var.collector_repository_arn]
    }
  }

  dynamic "statement" {
    for_each = each.value.collector ? [1] : []

    content {
      sid       = "ReadCollectorConfig"
      actions   = ["ssm:GetParameters"]
      resources = ["${local.arn}:ssm:${local.region}:${local.account_id}:parameter${var.collector_config_parameter}"]
    }
  }

  dynamic "statement" {
    for_each = length(each.value.secrets) > 0 ? [1] : []

    content {
      sid       = "ResolveOwnDbSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = [for s in each.value.secrets : local.secret_arn_pattern[s]]
    }
  }

  # The RDS-managed master secret has a generated name (rds!db-<uuid>), so it is matched by
  # the tag RDS puts on it: the ARN of the one instance whose master it is.
  dynamic "statement" {
    for_each = each.value.rds_master ? [1] : []

    content {
      sid       = "ResolveRdsMasterSecret"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = ["${local.arn}:secretsmanager:${local.region}:${local.account_id}:secret:rds!db-*"]

      condition {
        test     = "StringEquals"
        variable = "secretsmanager:ResourceTag/aws:rds:primaryDBInstanceArn"
        values   = ["${local.arn}:rds:${local.region}:${local.account_id}:db:${var.db.instance_identifier}"]
      }
    }
  }

  dynamic "statement" {
    for_each = length(each.value.secrets) > 0 || each.value.rds_master ? [1] : []

    content {
      sid       = "SecretsAreOnTheKey"
      actions   = ["kms:Decrypt"]
      resources = [var.kms_key_arn]

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = [local.kms_via["secretsmanager"]]
      }
    }
  }
}
