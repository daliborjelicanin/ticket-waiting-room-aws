# The queue-position socket (runbook-websocket-queue.md; retro §3). Server -> client only:
# $connect and $disconnect are API Gateway service integrations straight onto DynamoDB, no
# Lambda (CLAUDE.md: a function only where something has to decide), and there is no $default
# route, so a client frame gets API Gateway's own error. The notifier pushes frames through the
# management endpoint (output management_endpoint) with the role from modules/iam.
#
# A non-proxy WebSocket route needs three response pieces beyond the integration, and missing
# any one is a 500 handshake WITH THE WRITE COMMITTED (Phase 1 took four attempts):
#   1. an integration response keyed "/200/" (selection is by status code; "$default" is
#      accepted and can never match),
#   2. its response template, with the template selection expression set,
#   3. a route response, with the route's response selection expression set.

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  # API Gateway reads "\$default" as the literal key $default (an unescaped one is an expression).
  default_selection = "\\$default"

  routes = {
    "$connect"    = { action = "PutItem", template = "connect.json.vtl" }
    "$disconnect" = { action = "DeleteItem", template = "disconnect.json.vtl" }
  }
}

resource "aws_apigatewayv2_api" "this" {
  name                       = var.name
  protocol_type              = "WEBSOCKET"
  route_selection_expression = "$request.body.action"
}

resource "aws_apigatewayv2_integration" "route" {
  for_each = local.routes

  api_id             = aws_apigatewayv2_api.this.id
  integration_type   = "AWS"
  integration_method = "POST"
  integration_uri    = "arn:${data.aws_partition.current.partition}:apigateway:${data.aws_region.current.region}:dynamodb:action/${each.value.action}"
  credentials_arn    = var.integration_role_arn

  passthrough_behavior          = "NEVER"
  template_selection_expression = local.default_selection
  request_templates = {
    "$default" = templatefile("${path.module}/templates/${each.value.template}", {
      table_name = var.connections_table_name
    })
  }
}

resource "aws_apigatewayv2_integration_response" "route" {
  for_each = local.routes

  api_id                        = aws_apigatewayv2_api.this.id
  integration_id                = aws_apigatewayv2_integration.route[each.key].id
  integration_response_key      = "/200/" # not "$default": see the header (retro §3)
  template_selection_expression = local.default_selection
  response_templates            = { "$default" = "" }
}

resource "aws_apigatewayv2_route" "this" {
  for_each = local.routes

  api_id                              = aws_apigatewayv2_api.this.id
  route_key                           = each.key
  target                              = "integrations/${aws_apigatewayv2_integration.route[each.key].id}"
  route_response_selection_expression = "$default"
}

resource "aws_apigatewayv2_route_response" "this" {
  for_each = local.routes

  api_id             = aws_apigatewayv2_api.this.id
  route_id           = aws_apigatewayv2_route.this[each.key].id
  route_response_key = "$default"
}

# Access log: one line per connect, disconnect and failed frame. No query string, so neither
# the queue token nor (from slice 6) a connect ticket is written down (D18).
resource "aws_cloudwatch_log_group" "access" {
  name              = var.access_log_group_name
  retention_in_days = var.log_retention_days
}

resource "aws_apigatewayv2_stage" "this" {
  api_id      = aws_apigatewayv2_api.this.id
  name        = var.stage_name
  auto_deploy = true # a route or integration change deploys itself; no deployment to forget

  # Execution logs off: they go to an API-Gateway-Execution-Logs_* group with no retention.
  default_route_settings {
    logging_level            = "OFF"
    data_trace_enabled       = false
    detailed_metrics_enabled = false
    throttling_rate_limit    = var.connect_throttle.rate_limit
    throttling_burst_limit   = var.connect_throttle.burst_limit
  }

  # How fast anyone can open sockets: the WebSocket stand-in for a WAF rate rule, which cannot
  # attach here (D18).
  route_settings {
    route_key                = aws_apigatewayv2_route.this["$connect"].route_key
    logging_level            = "OFF"
    data_trace_enabled       = false
    detailed_metrics_enabled = false
    throttling_rate_limit    = var.connect_throttle.rate_limit
    throttling_burst_limit   = var.connect_throttle.burst_limit
  }

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.access.arn
    format = jsonencode({
      requestId         = "$context.requestId"
      requestTime       = "$context.requestTimeEpoch"
      ip                = "$context.identity.sourceIp"
      eventType         = "$context.eventType"
      routeKey          = "$context.routeKey"
      connectionId      = "$context.connectionId"
      status            = "$context.status"
      integrationStatus = "$context.integration.status"
      integrationError  = "$context.integration.error"
      error             = "$context.error.message"
    })
  }

  lifecycle {
    precondition {
      condition     = var.account_logging_role_arn != ""
      error_message = "API Gateway's account CloudWatch role must be set before a stage can write access logs."
    }
  }

  # The routes' response wiring must exist before the first auto-deployment.
  depends_on = [aws_apigatewayv2_route_response.this, aws_apigatewayv2_integration_response.route]
}
