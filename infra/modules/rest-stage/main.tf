# The session half of the REST edge (D5): VPC Link v2, the /{proxy+} private integration onto
# the internal ALB, the deployment and the stage. The API itself, its resources and methods
# live in foundation (modules/rest-api), so its id and invoke URL never change; these pieces
# reference the ALB's ARN, a session value, so they are created and destroyed with it.
#
# The link is v2 (aws_apigatewayv2_vpc_link, the resource HTTP APIs use), not the classic
# aws_api_gateway_vpc_link, which is v1 and NLB-only: the common trap (D5).

resource "aws_apigatewayv2_vpc_link" "this" {
  name               = var.vpc_link_name
  subnet_ids         = var.subnet_ids
  security_group_ids = [var.vpc_link_security_group_id]
}

resource "aws_api_gateway_integration" "proxy" {
  rest_api_id = var.rest_api_id
  resource_id = var.proxy_resource_id
  http_method = var.proxy_http_method

  type                    = "HTTP_PROXY"
  integration_http_method = "ANY"
  connection_type         = "VPC_LINK"
  connection_id           = aws_apigatewayv2_vpc_link.this.id
  # The ALB itself. A stage variable is rejected here (D5), which is why this lives in session.
  integration_target = var.alb_arn
  # Host and path sent to the ALB; the port is the listener's.
  # HTTP inside the VPC, to the internal ALB only its SG pair reaches (D5; TLS needs the domain, D20).
  # noinspection HttpUrlsUsage
  uri = "http://${var.alb_dns_name}/{proxy}"

  request_parameters = {
    "integration.request.path.proxy" = "method.request.path.proxy"
  }

  timeout_milliseconds = 29000
}

# Queue join and the position poll (D6): the same private integration, on the resource that
# exists so queue join can be throttled on its own.
resource "aws_api_gateway_integration" "queue" {
  for_each = toset(var.queue_http_methods)

  rest_api_id = var.rest_api_id
  resource_id = var.queue_resource_id
  http_method = each.key

  type                    = "HTTP_PROXY"
  integration_http_method = each.key
  connection_type         = "VPC_LINK"
  connection_id           = aws_apigatewayv2_vpc_link.this.id
  integration_target      = var.alb_arn
  # The resource path is the service path; {eventId} is filled from the mapping below.
  # HTTP inside the VPC, to the internal ALB only its SG pair reaches (D5; TLS needs the domain, D20).
  # noinspection HttpUrlsUsage
  uri = "http://${var.alb_dns_name}${var.queue_path}"

  request_parameters = {
    "integration.request.path.eventId" = "method.request.path.eventId"
  }

  timeout_milliseconds = 29000
}

# What the integrations serve, from their arguments only. Hashing the whole resource objects
# (as at first) moved the hash once after every create: attributes known only after apply read
# back differently, and the next apply replaced the deployment for nothing (found 2026-11-19,
# the first full session; foundation's definition_hash had the same cause in slice 2).
locals {
  integration_inputs = [
    for i in concat([aws_api_gateway_integration.proxy], values(aws_api_gateway_integration.queue)) : [
      i.http_method, i.type, i.integration_http_method, i.connection_type, i.connection_id,
      i.integration_target, i.uri, i.request_parameters, i.timeout_milliseconds,
    ]
  ]
}

resource "aws_api_gateway_deployment" "this" {
  rest_api_id = var.rest_api_id

  # A deployment is a snapshot: without a trigger, a changed method or integration is never
  # served (retro §3). Foundation's side arrives as its published definition_hash.
  triggers = {
    redeployment = sha1(jsonencode([var.definition_hash, local.integration_inputs]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "this" {
  rest_api_id   = var.rest_api_id
  deployment_id = aws_api_gateway_deployment.this.id
  stage_name    = var.stage_name

  xray_tracing_enabled = true

  # No query string and no headers: nothing here can carry a token (D6).
  access_log_settings {
    destination_arn = var.access_log_group_arn
    format = jsonencode({
      requestId          = "$context.requestId"
      requestTime        = "$context.requestTimeEpoch"
      ip                 = "$context.identity.sourceIp"
      httpMethod         = "$context.httpMethod"
      resourcePath       = "$context.resourcePath"
      status             = "$context.status"
      responseLength     = "$context.responseLength"
      latency            = "$context.responseLatency"
      integrationStatus  = "$context.integration.status"
      integrationLatency = "$context.integration.latency"
      integrationError   = "$context.integration.error"
      wafStatus          = "$context.waf.status"
      error              = "$context.error.message"
    })
  }
}

# The free backstop (D6): WAF decides *who*, throttling bounds *how much*. Only the two buyer
# entry points; everything else, the saga's calls included, keeps the account default.
resource "aws_api_gateway_method_settings" "entry" {
  for_each = local.entry_paths

  rest_api_id = var.rest_api_id
  stage_name  = aws_api_gateway_stage.this.stage_name
  # "events/{eventId}/purchase/POST": the resource path without its leading slash, then the method.
  method_path = "${trimprefix(each.value, "/")}/POST"

  settings {
    throttling_rate_limit  = var.throttle_rate[each.key]
    throttling_burst_limit = var.throttle_burst[each.key]
  }
}

locals {
  entry_paths = {
    queue    = var.queue_path
    purchase = var.purchase_path
  }
}
