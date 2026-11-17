# The private integration (D5): a v2 link, the ALB as the target, the proxy path carried
# through, and a stage that traces and logs. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan
}

variables {
  rest_api_id                = "api123"
  proxy_resource_id          = "res123"
  proxy_http_method          = "ANY"
  definition_hash            = "abc"
  stage_name                 = "test"
  access_log_group_arn       = "arn:aws:logs:eu-central-1:aws:log-group:/twr/test/apigw/edge"
  vpc_link_name              = "twr-test-edge"
  subnet_ids                 = ["subnet-a", "subnet-b"]
  vpc_link_security_group_id = "sg-vpclink"
  alb_arn                    = "arn:aws:elasticloadbalancing:eu-central-1:aws:loadbalancer/app/twr-test-alb/0123456789abcdef"
  alb_dns_name               = "internal-twr-test-alb.example"
}

run "private_integration_onto_the_alb" {
  command = plan

  assert {
    condition = (
      aws_api_gateway_integration.proxy.type == "HTTP_PROXY"
      && aws_api_gateway_integration.proxy.connection_type == "VPC_LINK"
      && aws_api_gateway_integration.proxy.integration_target == var.alb_arn
    )
    error_message = "HTTP_PROXY over a VPC link, with the ALB as the target (D5)."
  }

  assert {
    condition     = aws_api_gateway_integration.proxy.uri == "http://internal-twr-test-alb.example/{proxy}"
    error_message = "The URI carries the ALB host and the proxy path."
  }

  assert {
    condition     = aws_api_gateway_integration.proxy.request_parameters["integration.request.path.proxy"] == "method.request.path.proxy"
    error_message = "The proxy path parameter is mapped through, or every request reaches /."
  }

  assert {
    condition     = contains(aws_apigatewayv2_vpc_link.this.security_group_ids, "sg-vpclink") && length(aws_apigatewayv2_vpc_link.this.security_group_ids) == 1
    error_message = "The link's ENIs carry the vpclink SG only."
  }
}

run "stage_traces_and_logs" {
  command = plan

  assert {
    condition     = aws_api_gateway_stage.this.xray_tracing_enabled
    error_message = "X-Ray tracing on the stage."
  }

  assert {
    condition     = aws_api_gateway_stage.this.access_log_settings[0].destination_arn == var.access_log_group_arn
    error_message = "Access logs go to foundation's group."
  }

  assert {
    condition = (
      !strcontains(aws_api_gateway_stage.this.access_log_settings[0].format, "querystring")
      && !strcontains(lower(aws_api_gateway_stage.this.access_log_settings[0].format), "authorization")
    )
    error_message = "No query string or Authorization header in the access log (D6)."
  }
}

run "nlb_target_refused" {
  command = plan

  variables {
    alb_arn = "arn:aws:elasticloadbalancing:eu-central-1:aws:loadbalancer/net/twr-test-nlb/0123456789abcdef"
  }

  expect_failures = [var.alb_arn]
}
