# Foundation alarms (D16): the settings that make an alarm silently useless are pinned on every
# one, each alarm reaches the topic both ways, and each description links its runbook section.
# Plan against a mocked provider: no AWS.

mock_provider "aws" {
  override_during = plan

  mock_data "aws_region" {
    defaults = { region = "eu-central-1" }
  }

  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

override_resource {
  target          = aws_sns_topic.ops_alarms
  override_during = plan
  values          = { arn = "arn:aws:sns:eu-central-1:aws:twr-test-ops-alarms" }
}

variables {
  name_prefix = "twr-test"
  kms_key_arn = "arn:aws:kms:eu-central-1:aws:key/test"
  alert_email = "ops@example.test"
  repository  = "owner/repo"

  log_groups = {
    "ecs/waiting-room" = "/twr/test/ecs/waiting-room"
    "ecs/checkout"     = "/twr/test/ecs/checkout"
    metrics            = "/twr/test/metrics"
    saga               = "/aws/vendedlogs/states/twr-test-checkout-saga"
  }
  ecs_exec_log_group         = "/twr/test/ecs-exec"
  collector_config_parameter = "/twr/test/otel-collector-config"

  queue_names         = { qr-generation = "twr-test-qr-generation", ticket-issuance = "twr-test-ticket-issuance" }
  visibility_timeouts = { qr-generation = 360, ticket-issuance = 30 }
  dlq_names = {
    qr-generation                                                  = "twr-test-qr-generation-dlq"
    "delivery/purchase-confirmed/purchase-confirmed-notifications" = "twr-test-purchase-confirmed-to-purchase-confirmed-notifications-dlq"
  }
  lambda_function_names = { hold-cleanup = "twr-test-hold-cleanup", qr-generation = "twr-test-qr-generation" }
  lambda_log_groups     = { hold-cleanup = "/aws/lambda/twr-test-hold-cleanup", qr-generation = "/aws/lambda/twr-test-qr-generation" }

  websocket_api_id           = "wsapi"
  websocket_stage_name       = "test"
  websocket_access_log_group = "/twr/test/apigw/queue-socket"
  saga_state_machine_arn     = "arn:aws:states:eu-central-1:aws:stateMachine:twr-test-checkout-saga"
  seat_table_name            = "twr-test-seat-inventory"
  waf_acl_name               = "twr-test-edge"
}

run "every_alarm_is_pinned_and_reaches_the_topic" {
  command = plan

  assert {
    condition = alltrue([for a in concat(
      values(aws_cloudwatch_metric_alarm.dlq),
      values(aws_cloudwatch_metric_alarm.queue_age),
      values(aws_cloudwatch_metric_alarm.lambda),
      [aws_cloudwatch_metric_alarm.admission_publish],
      ) : (
      a.treat_missing_data == "notBreaching"
      && a.alarm_actions == toset([aws_sns_topic.ops_alarms.arn])
      && a.ok_actions == toset([aws_sns_topic.ops_alarms.arn])
      && a.evaluation_periods == 1 && a.datapoints_to_alarm == 1
      && strcontains(a.alarm_description, "https://github.com/owner/repo/blob/main/docs/runbook-alarms.md#")
    )])
    error_message = "Every alarm: treat_missing_data pinned, alarm and OK actions on ops-alarms, a runbook link (D16)."
  }

  assert {
    condition     = length(aws_cloudwatch_metric_alarm.dlq) + length(aws_cloudwatch_metric_alarm.queue_age) + length(aws_cloudwatch_metric_alarm.lambda) + 1 == 7
    error_message = "One alarm per DLQ (subscription DLQs included), per main queue, per function, plus admission publish."
  }
}

run "thresholds_are_derived" {
  command = plan

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.queue_age["qr-generation"].threshold == 1080
      && aws_cloudwatch_metric_alarm.queue_age["ticket-issuance"].threshold == 90
    )
    error_message = "A queue is stuck at 3 x its visibility timeout (360 -> 1080, 30 -> 90)."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.dlq["delivery/purchase-confirmed/purchase-confirmed-notifications"].dimensions["QueueName"] == "twr-test-purchase-confirmed-to-purchase-confirmed-notifications-dlq"
      && aws_cloudwatch_metric_alarm.dlq["delivery/purchase-confirmed/purchase-confirmed-notifications"].alarm_name == "twr-test-dlq-delivery-purchase-confirmed-purchase-confirmed-notifications"
      && aws_cloudwatch_metric_alarm.dlq["qr-generation"].threshold == 0
    )
    error_message = "A DLQ alarms on its first message; the subscription DLQ too, under a name without slashes."
  }

  assert {
    condition = anytrue([for q in aws_cloudwatch_metric_alarm.lambda["qr-generation"].metric_query : (
      q.id == "failures" && q.expression == "errors + throttles" && q.return_data
    )])
    error_message = "Lambda: errors and throttles in one metric-math alarm."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.admission_publish.metric_name == "waiting_room.admission.publish.failures"
      && aws_cloudwatch_metric_alarm.admission_publish.namespace == "TicketWaitingRoom"
      && aws_cloudwatch_metric_alarm.admission_publish.dimensions["service"] == "waiting-room-service"
    )
    error_message = "The admission alarm reads the counter WaitingRoomMetrics exports."
  }
}

run "log_groups_have_retention" {
  command = plan

  assert {
    condition     = alltrue([for g in values(aws_cloudwatch_log_group.service) : (g.retention_in_days == 3)]) && aws_cloudwatch_log_group.audit.retention_in_days == 30
    error_message = "Every declared group has a retention; the ECS Exec audit keeps 30 days."
  }
}

run "templates_render_with_this_stack_names" {
  command = plan

  assert {
    condition = (
      strcontains(aws_ssm_parameter.collector_config.value, "log_group_name: /twr/test/metrics")
      && strcontains(aws_ssm_parameter.collector_config.value, "namespace: TicketWaitingRoom")
      && strcontains(aws_ssm_parameter.collector_config.value, "retain_initial_value_of_delta_metric: true")
    )
    error_message = "The collector writes EMF to the declared metrics group, keeping the first delta."
  }

  assert {
    condition = (
      length(jsondecode(aws_cloudwatch_dashboard.this.dashboard_body).widgets) == 14
      && strcontains(aws_cloudwatch_dashboard.this.dashboard_body, "twr-test-purchase-confirmed-to-purchase-confirmed-notifications-dlq")
      && strcontains(aws_cloudwatch_dashboard.this.dashboard_body, "twr-test-checkout-saga")
      && !strcontains(aws_cloudwatch_dashboard.this.dashboard_body, "account-id")
    )
    error_message = "The dashboard is valid JSON, lists every DLQ and carries this stack's names."
  }

  # PutDashboard's own rule, which valid JSON alone does not show: every metric row is an array
  # (of strings, with an optional options object last). A flatten() once broke it (2026-11-16).
  assert {
    condition = alltrue([for w in jsondecode(aws_cloudwatch_dashboard.this.dashboard_body).widgets : (
      w.type != "metric" || alltrue([for row in w.properties.metrics : (can(length(row)) && can(row[0]) && !can(tonumber(row)))])
    )])
    error_message = "Every metric row in every widget is an array, as PutDashboard requires."
  }

  assert {
    condition     = length([for w in jsondecode(aws_cloudwatch_dashboard.this.dashboard_body).widgets : w if strcontains(try(w.properties.title, ""), "Lambdas") && try(length(w.properties.metrics), 0) == 6]) == 1
    error_message = "The Lambda widget has three rows per function (errors, throttles, p99)."
  }
}

run "queries_cover_the_runbooks" {
  command = plan

  assert {
    condition = (
      length(aws_cloudwatch_query_definition.this) == 6
      && aws_cloudwatch_query_definition.this["service-errors"].log_group_names == tolist(["/twr/test/ecs/checkout", "/twr/test/ecs/waiting-room"])
    )
    error_message = "Six saved queries; service-errors spans every ECS service group and nothing else."
  }
}
