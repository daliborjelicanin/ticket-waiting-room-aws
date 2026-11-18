# The public edge (D5, D6): one REGIONAL REST API, the only way in. Two paths:
#   POST /events/{eventId}/purchase  Cognito authorizer -> states:StartExecution, no Lambda
#   ANY  /{proxy+}                   no authorizer here: every service verifies the JWT itself
#                                    (security-starter), so the edge adds no second opinion
#
# The stack split (D5, retro §1): what references a session value lives in session. The
# /{proxy+} integration needs the ALB's ARN (a stage variable is rejected there), so that
# integration, the deployment and the stage are the session stack's. Everything here survives
# every session, so the API id, and with it the invoke URL every QR encodes, stays fixed for
# the life of foundation (D19, D20). Its stage name is fixed too, which is what lets the URL be
# computed here, before any stage exists.

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  queue_methods = ["GET", "POST"]

  invoke_url = "https://${aws_api_gateway_rest_api.this.id}.execute-api.${data.aws_region.current.region}.${data.aws_partition.current.dns_suffix}/${var.stage_name}"
}

resource "aws_api_gateway_rest_api" "this" {
  name        = var.name
  description = "Public edge: purchase -> saga, everything else -> internal ALB through VPC Link v2."

  # Edge-optimised fronts the API with a CloudFront distribution our WAF cannot attach to (D5).
  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

resource "aws_api_gateway_authorizer" "users" {
  name            = "users"
  rest_api_id     = aws_api_gateway_rest_api.this.id
  type            = "COGNITO_USER_POOLS"
  provider_arns   = [var.user_pool_arn]
  identity_source = "method.request.header.Authorization"
}

# --- /events/{eventId}/purchase ------------------------------------------------------------

resource "aws_api_gateway_resource" "events" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_rest_api.this.root_resource_id
  path_part   = "events"
}

resource "aws_api_gateway_resource" "event" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_resource.events.id
  path_part   = "{eventId}"
}

resource "aws_api_gateway_resource" "purchase" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_resource.event.id
  path_part   = "purchase"
}

resource "aws_api_gateway_method" "purchase" {
  rest_api_id   = aws_api_gateway_rest_api.this.id
  resource_id   = aws_api_gateway_resource.purchase.id
  http_method   = "POST"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = aws_api_gateway_authorizer.users.id

  request_parameters = { "method.request.path.eventId" = true }
}

resource "aws_api_gateway_integration" "purchase" {
  rest_api_id             = aws_api_gateway_rest_api.this.id
  resource_id             = aws_api_gateway_resource.purchase.id
  http_method             = aws_api_gateway_method.purchase.http_method
  type                    = "AWS"
  integration_http_method = "POST"
  uri                     = "arn:${data.aws_partition.current.partition}:apigateway:${data.aws_region.current.region}:states:action/StartExecution"
  credentials             = var.start_execution_role_arn

  # Only JSON reaches the template; any other content type is a 415, never an untemplated body.
  passthrough_behavior = "NEVER"
  request_templates = {
    "application/json" = templatefile("${path.module}/templates/purchase-request.vtl", {
      saga_state_machine_arn = var.saga_state_machine_arn
      services_base_url      = local.invoke_url
    })
  }
}

# Without a 4xx and a 5xx mapping the default response is the only one, and a refused or
# failed StartExecution reaches the client as a 202.
locals {
  purchase_responses = {
    "202" = { pattern = "", template = "purchase-accepted.vtl" }
    "400" = { pattern = "4\\d{2}", template = "purchase-refused.vtl" }
    "502" = { pattern = "5\\d{2}", template = null }
  }
}

resource "aws_api_gateway_method_response" "purchase" {
  for_each = local.purchase_responses

  rest_api_id = aws_api_gateway_rest_api.this.id
  resource_id = aws_api_gateway_resource.purchase.id
  http_method = aws_api_gateway_method.purchase.http_method
  status_code = each.key
}

resource "aws_api_gateway_integration_response" "purchase" {
  for_each = local.purchase_responses

  rest_api_id       = aws_api_gateway_rest_api.this.id
  resource_id       = aws_api_gateway_resource.purchase.id
  http_method       = aws_api_gateway_method.purchase.http_method
  status_code       = aws_api_gateway_method_response.purchase[each.key].status_code
  selection_pattern = each.value.pattern
  response_templates = {
    "application/json" = (
      each.value.template == null
      ? "{\"error\": \"PURCHASE_NOT_STARTED\"}"
      : file("${path.module}/templates/${each.value.template}")
    )
  }

  depends_on = [aws_api_gateway_integration.purchase]
}

# --- /events/{eventId}/queue ---------------------------------------------------------------
# The other buyer entry point (D6). It needs a resource of its own only so that stage method
# settings can throttle queue join apart from everything else under /{proxy+}. GET (the
# position poll, same path) is declared beside it: a request that matches a resource but not
# one of its methods is refused there, not passed on to /{proxy+}. Integrations: session.

resource "aws_api_gateway_resource" "queue" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_resource.event.id
  path_part   = "queue"
}

resource "aws_api_gateway_method" "queue" {
  for_each = toset(local.queue_methods)

  rest_api_id   = aws_api_gateway_rest_api.this.id
  resource_id   = aws_api_gateway_resource.queue.id
  http_method   = each.key
  authorization = "NONE" # the JWT is checked by waiting-room (security-starter), as on /{proxy+}

  request_parameters = { "method.request.path.eventId" = true }
}

# --- /{proxy+} -----------------------------------------------------------------------------
# The method lives here; its VPC_LINK integration to the internal ALB is the session stack's.

resource "aws_api_gateway_resource" "proxy" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_rest_api.this.root_resource_id
  path_part   = "{proxy+}"
}

resource "aws_api_gateway_method" "proxy" {
  rest_api_id   = aws_api_gateway_rest_api.this.id
  resource_id   = aws_api_gateway_resource.proxy.id
  http_method   = "ANY"
  authorization = "NONE" # the JWT is checked by the service (security-starter), see the header

  request_parameters = { "method.request.path.proxy" = true }
}

# --- what the session stack's deployment needs --------------------------------------------

# The REST stage's access log group, declared here so its name and retention outlive sessions.
resource "aws_cloudwatch_log_group" "access" {
  name              = var.access_log_group_name
  retention_in_days = var.log_retention_days
}

locals {
  # Everything a deployment snapshots from this side. The session deployment's `triggers`
  # include it, so a change here redeploys the stage (Phase 1: method changes not deployed,
  # retro §3).
  definition_hash = sha1(jsonencode([
    aws_api_gateway_authorizer.users,
    aws_api_gateway_resource.events,
    aws_api_gateway_resource.event,
    aws_api_gateway_resource.purchase,
    aws_api_gateway_resource.proxy,
    aws_api_gateway_resource.queue,
    aws_api_gateway_method.purchase,
    aws_api_gateway_method.queue,
    aws_api_gateway_method.proxy,
    aws_api_gateway_integration.purchase,
    aws_api_gateway_method_response.purchase,
    aws_api_gateway_integration_response.purchase,
  ]))
}
