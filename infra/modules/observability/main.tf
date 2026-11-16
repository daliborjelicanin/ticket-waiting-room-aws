# Observability that outlives sessions (D16): the ops-alarms topic every human-facing alert
# goes to, the log groups the services and the saga write to, the collector's configuration,
# the dashboard, the saved Logs Insights queries and the foundation alarms. The alarms on
# session resources (saga, ALB, tasks) live in the session stack, next to what they watch.
#
# The rule for an alarm (D16): it exists only if a human has to DO something when it fires, and
# its description links the runbook section that says what. Every alarm pins
# treat_missing_data (SQS stops publishing for an idle queue, and the default leaves the alarm
# in INSUFFICIENT_DATA, neither green nor red) and notifies on recovery as well.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  runbook = "https://github.com/${var.repository}/blob/main/docs/runbook-alarms.md"
}

# --- ops-alarms ----------------------------------------------------------------------------

resource "aws_sns_topic" "ops_alarms" {
  name              = "${var.name_prefix}-ops-alarms"
  kms_master_key_id = var.kms_key_arn
}

data "aws_iam_policy_document" "ops_alarms" {
  statement {
    sid       = "AwsAlertSourcesPublish"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.ops_alarms.arn]

    principals {
      type = "Service"
      identifiers = [
        "budgets.amazonaws.com",
        "cloudwatch.amazonaws.com",
        "costalerts.amazonaws.com",
        "events.amazonaws.com",
      ]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "ops_alarms" {
  arn    = aws_sns_topic.ops_alarms.arn
  policy = data.aws_iam_policy_document.ops_alarms.json
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.ops_alarms.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# --- log groups ----------------------------------------------------------------------------
# Declared here with a retention, so nothing creates them implicitly with "never expire"
# (retro §3). Lambda and API Gateway groups are declared by the modules that own them.

resource "aws_cloudwatch_log_group" "service" {
  for_each = var.log_groups

  name              = each.value
  retention_in_days = var.log_retention_days
}

# ECS Exec sessions are the audit trail of every shell opened in a task (D9, D12): kept longer.
resource "aws_cloudwatch_log_group" "audit" {
  name              = var.ecs_exec_log_group
  retention_in_days = var.audit_log_retention_days
}

# --- collector configuration ---------------------------------------------------------------

resource "aws_ssm_parameter" "collector_config" {
  name = var.collector_config_parameter
  type = "String"
  value = templatefile("${path.module}/templates/otel-collector-config.yaml.tftpl", {
    parameter_name    = var.collector_config_parameter
    namespace         = var.metrics_namespace
    metrics_log_group = aws_cloudwatch_log_group.service["metrics"].name
  })
}

# --- dashboard -----------------------------------------------------------------------------

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.name_prefix
  dashboard_body = templatefile("${path.module}/templates/dashboard.json.tftpl", {
    name_prefix            = var.name_prefix
    region                 = data.aws_region.current.region
    saga_state_machine_arn = var.saga_state_machine_arn
    seat_table_name        = var.seat_table_name
    waf_acl_name           = var.waf_acl_name
    websocket_api_id       = var.websocket_api_id
    stage_name             = var.websocket_stage_name
    dlq_metrics = jsonencode([for k, name in var.dlq_names :
      ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", name, { label = k }]
    ])
    lambda_metrics = jsonencode(flatten([for k, name in var.lambda_function_names : [
      ["AWS/Lambda", "Errors", "FunctionName", name, { label = "${k} errors", stat = "Sum" }],
      ["AWS/Lambda", "Throttles", "FunctionName", name, { label = "${k} throttles", stat = "Sum" }],
      ["AWS/Lambda", "Duration", "FunctionName", name, { label = "${k} p99 ms", stat = "p99", yAxis = "right" }],
    ]]))
  })
}

# --- saved Logs Insights queries (free) ----------------------------------------------------
# The searches the runbooks do by hand, saved under the project's folder in the console.

locals {
  queries = {
    "service-errors" = {
      groups = [for k, g in var.log_groups : g if startswith(k, "ecs/")]
      query  = <<-EOT
        fields @timestamp, @logStream, @message
        | filter @message like / (ERROR|WARN) /
        | sort @timestamp desc
        | limit 200
      EOT
    }
    "admission-publish-failures" = {
      groups = [var.log_groups["ecs/waiting-room"]]
      query  = <<-EOT
        fields @timestamp, @message
        | filter @message like /could not publish the admission/
        | sort @timestamp desc
      EOT
    }
    "lambda-cold-starts" = {
      groups = values(var.lambda_log_groups)
      query  = <<-EOT
        filter @type = "REPORT" or @message like /INIT_REPORT/
        | stats count(@requestId) as invocations, count(@initDuration) as coldStarts,
                max(@initDuration) as maxInitMs, pct(@duration, 99) as p99Ms,
                sum(strcontains(@message, "Status: timeout")) as initTimeouts
          by @log, bin(1h)
      EOT
    }
    "lambda-record-failures" = {
      groups = values(var.lambda_log_groups)
      query  = <<-EOT
        fields @timestamp, @log, @message
        | filter @message like /failed (QR|push)|pruned closed|lost race/
        | sort @timestamp desc
        | limit 200
      EOT
    }
    "hold-cleanup-runs" = {
      groups = [var.lambda_log_groups["hold-cleanup"]]
      query  = <<-EOT
        fields @timestamp, @message
        | filter @message like /hold cleanup complete/
        | sort @timestamp desc
      EOT
    }
    "socket-handshake-failures" = {
      groups = [var.websocket_access_log_group]
      query  = <<-EOT
        fields @timestamp, eventType, routeKey, status, error, integrationError
        | filter status != "200"
        | stats count(*) as n by routeKey, status, error
      EOT
    }
  }

  # Flat maps (infra/CLAUDE.md): each.value.<attr> over a for-built map is unresolvable there.
  query_groups = { for k, q in local.queries : k => q.groups }
  query_text   = { for k, q in local.queries : k => q.query }
}

resource "aws_cloudwatch_query_definition" "this" {
  for_each = local.query_text

  name            = "${var.name_prefix}/${each.key}"
  log_group_names = local.query_groups[each.key]
  query_string    = each.value
}

# --- foundation alarms (D16) ---------------------------------------------------------------

# A message gave up: a human replays it or explains it.
resource "aws_cloudwatch_metric_alarm" "dlq" {
  for_each = var.dlq_names

  alarm_name        = "${var.name_prefix}-dlq-${replace(each.key, "/", "-")}"
  alarm_description = "A message is in ${each.value}. What to do: ${local.runbook}#a-dlq-is-not-empty"

  namespace   = "AWS/SQS"
  metric_name = "ApproximateNumberOfMessagesVisible"
  dimensions  = { QueueName = each.value }
  statistic   = "Maximum"
  period      = 60

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching" # an idle queue publishes nothing; that is healthy

  alarm_actions = [aws_sns_topic.ops_alarms.arn]
  ok_actions    = [aws_sns_topic.ops_alarms.arn]
}

# A stuck consumer, long before anything reaches a DLQ: the oldest message has waited longer
# than its consumer could take to process it several times over.
resource "aws_cloudwatch_metric_alarm" "queue_age" {
  for_each = var.queue_names

  alarm_name        = "${var.name_prefix}-queue-age-${each.key}"
  alarm_description = "The oldest message on ${each.value} is older than ${var.queue_age_multiplier} x its visibility timeout. What to do: ${local.runbook}#a-queue-is-stuck"

  namespace   = "AWS/SQS"
  metric_name = "ApproximateAgeOfOldestMessage"
  dimensions  = { QueueName = each.value }
  statistic   = "Maximum"
  period      = 60

  comparison_operator = "GreaterThanThreshold"
  threshold           = var.queue_age_multiplier * var.visibility_timeouts[each.key]
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.ops_alarms.arn]
  ok_actions    = [aws_sns_topic.ops_alarms.arn]
}

# The notifier dropped a burst, or QR / cleanup is failing. Errors and throttles in one
# metric-math alarm per function.
resource "aws_cloudwatch_metric_alarm" "lambda" {
  for_each = var.lambda_function_names

  alarm_name        = "${var.name_prefix}-lambda-${each.key}"
  alarm_description = "${each.value} reported errors or throttles. What to do: ${local.runbook}#a-lambda-errors-or-throttles"

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching" # no invocations is not a failure

  metric_query {
    id          = "failures"
    expression  = "errors + throttles"
    label       = "errors + throttles"
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      namespace   = "AWS/Lambda"
      metric_name = "Errors"
      dimensions  = { FunctionName = each.value }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id = "throttles"
    metric {
      namespace   = "AWS/Lambda"
      metric_name = "Throttles"
      dimensions  = { FunctionName = each.value }
      stat        = "Sum"
      period      = 300
    }
  }

  alarm_actions = [aws_sns_topic.ops_alarms.arn]
  ok_actions    = [aws_sns_topic.ops_alarms.arn]
}

# A buyer was admitted but never told: waiting-room-service swallows the publish failure (the
# admission already happened in Redis) and counts it. The counter is exported by the collector,
# so the alarm reads it directly instead of a metric filter on the log line.
resource "aws_cloudwatch_metric_alarm" "admission_publish" {
  alarm_name        = "${var.name_prefix}-admission-publish-failures"
  alarm_description = "waiting-room-service admitted tokens it could not announce. What to do: ${local.runbook}#admission-publish-failures"

  namespace   = var.metrics_namespace
  metric_name = "waiting_room.admission.publish.failures"
  dimensions  = { service = "waiting-room-service" }
  statistic   = "Sum"
  period      = 60

  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  treat_missing_data  = "notBreaching" # no session, no service, no metric

  alarm_actions = [aws_sns_topic.ops_alarms.arn]
  ok_actions    = [aws_sns_topic.ops_alarms.arn]
}
