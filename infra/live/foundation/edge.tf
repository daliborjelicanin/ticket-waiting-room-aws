# The two API Gateway APIs (D5, D18). Both live here so their ids, and with them every URL a
# QR or a client holds, survive every session. The REST API's /{proxy+} integration, its
# deployment and its stage are the session stack's (D5): they need the ALB.

module "websocket_api" {
  source = "../../modules/websocket-api"

  name                   = "${local.name_prefix}-queue-socket"
  stage_name             = local.api_stage_name
  connections_table_name = module.connections_table.name
  integration_role_arn   = module.iam.role_arns["websocket-apigw"]
  access_log_group_name  = local.log_groups.apigw["queue-socket"]
  # Read from the account setting, not the role: the stage then waits for the setting.
  account_logging_role_arn = aws_api_gateway_account.this.cloudwatch_role_arn
}

module "rest_api" {
  source = "../../modules/rest-api"

  name                     = "${local.name_prefix}-edge"
  stage_name               = local.api_stage_name
  user_pool_arn            = module.identity.user_pool_arn
  saga_state_machine_arn   = local.saga_state_machine_arn
  start_execution_role_arn = module.iam.role_arns["purchase-apigw"]
  access_log_group_name    = local.log_groups.apigw["edge"]
}

# Account-wide, per region: the role every REST and WebSocket stage writes access logs with.
resource "aws_api_gateway_account" "this" {
  cloudwatch_role_arn = module.iam.role_arns["apigateway-cloudwatch"]
}

data "aws_partition" "current" {}

data "aws_region" "current" {}
