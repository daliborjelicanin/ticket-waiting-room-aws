# Cost guards as code (D17). Budgets are the record, not the brake: billing data lags 8-24 h.
# Faster guards are the session reaper (D15) and Cost Anomaly Detection below.

locals {
  # Actual 80% and 100%, forecasted 100%, for the monthly budget (D17). Two flat maps keyed by
  # alert rather than one map of objects: IntelliJ's HCL plugin cannot resolve an attribute of
  # a dynamic block's iterator value (infra/CLAUDE.md).
  monthly_alert_type = {
    actual_80    = "ACTUAL"
    actual_100   = "ACTUAL"
    forecast_100 = "FORECASTED"
  }
  monthly_alert_threshold = {
    actual_80    = 80
    actual_100   = 100
    forecast_100 = 100
  }
}

resource "aws_budgets_budget" "monthly" {
  name         = "${var.name_prefix}-monthly"
  budget_type  = "COST"
  limit_amount = format("%.1f", var.monthly_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  dynamic "notification" {
    for_each = local.monthly_alert_type

    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = local.monthly_alert_threshold[notification.key]
      threshold_type             = "PERCENTAGE"
      notification_type          = notification.value
      subscriber_email_addresses = [var.alert_email]
      subscriber_sns_topic_arns  = [var.ops_alarms_topic_arn]
    }
  }
}

# The whole phase as one period: a custom date range, cumulative from phase_start.
resource "aws_budgets_budget" "phase" {
  name              = "${var.name_prefix}-phase2-total"
  budget_type       = "COST"
  limit_amount      = format("%.1f", var.phase_limit_usd)
  limit_unit        = "USD"
  time_unit         = "CUSTOM"
  time_period_start = "${var.phase_start}_00:00"
  time_period_end   = "${var.phase_end}_00:00"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
    subscriber_sns_topic_arns  = [var.ops_alarms_topic_arn]
  }
}

# Spend in a shape it has not seen before, e.g. a NAT Gateway appearing at 3 am (D17).
resource "aws_ce_anomaly_monitor" "services" {
  name              = "${var.name_prefix}-services"
  monitor_type      = "DIMENSIONAL"
  monitor_dimension = "SERVICE"
}

resource "aws_ce_anomaly_subscription" "ops_alarms" {
  name             = "${var.name_prefix}-anomalies"
  frequency        = "IMMEDIATE"
  monitor_arn_list = [aws_ce_anomaly_monitor.services.arn]

  subscriber {
    type    = "SNS"
    address = var.ops_alarms_topic_arn
  }

  threshold_expression {
    dimension {
      key           = "ANOMALY_TOTAL_IMPACT_ABSOLUTE"
      match_options = ["GREATER_THAN_OR_EQUAL"]
      values        = [tostring(var.anomaly_threshold_usd)]
    }
  }
}
