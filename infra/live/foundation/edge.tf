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

# WAF logs (D6). Here, not in session: the web ACL is destroyed after every session, and its
# logs must outlive it, or a block cannot be explained after the fact. WAF delivers only to a
# bucket named aws-waf-logs-*, the one exception to the twr- prefix (D25). SSE-S3 like the flow
# logs (D11): the Authorization header is redacted before delivery, so no credential lands here.
module "waf_log_bucket" {
  source = "../../modules/s3"

  name              = "aws-waf-logs-${local.name_prefix}"
  expiration_days   = 7
  force_destroy     = true # logs only
  extra_policy_json = data.aws_iam_policy_document.waf_log_delivery.json
}

data "aws_iam_policy_document" "waf_log_delivery" {
  statement {
    sid       = "WafLogDeliveryAclCheck"
    actions   = ["s3:GetBucketAcl"]
    resources = [module.waf_log_bucket.arn]

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:logs:${data.aws_region.current.region}:${var.account_id}:*"]
    }
  }

  statement {
    sid       = "WafLogDeliveryWrite"
    actions   = ["s3:PutObject"]
    resources = ["${module.waf_log_bucket.arn}/AWSLogs/${var.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:logs:${data.aws_region.current.region}:${var.account_id}:*"]
    }
  }
}

# Account-wide, per region: the role every REST and WebSocket stage writes access logs with.
resource "aws_api_gateway_account" "this" {
  cloudwatch_role_arn = module.iam.role_arns["apigateway-cloudwatch"]

  depends_on = [aws_cloudwatch_log_group.apigateway_welcome]
}

# Setting the account's role makes API Gateway write a "welcome" event into this group, which
# it creates with no retention if the group is missing (found by the sweep, 2026-11-16).
# Declared first, so it exists with a retention before the setting is made. (The group created
# on 2026-11-16 was adopted with a one-time import block, removed after it applied.)
resource "aws_cloudwatch_log_group" "apigateway_welcome" {
  name              = "/aws/apigateway/welcome"
  retention_in_days = 3
}


data "aws_partition" "current" {}

data "aws_region" "current" {}
