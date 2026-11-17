# By-the-hour resources, destroyed after every session (§2 "What lives where"). Slice 2 on.
#
# Everything foundation built arrives through SSM under /twr/<env>/foundation/ (D2), never
# through its state. A toggle off means the resource does not exist and costs nothing.

locals {
  name_prefix = "twr-${var.env}"
  ssm_prefix  = "/twr/${var.env}/foundation"

  # The parameters this root reads. Plain strings; the lists are split below.
  foundation_keys = [
    "network/app_subnet_ids",
    "network/sg/alb",
    "network/sg/vpclink",
    "apigw/rest/id",
    "apigw/rest/proxy_resource_id",
    "apigw/rest/proxy_http_method",
    "apigw/rest/definition_hash",
    "apigw/rest/access_log_arn",
    "apigw/rest/stage_name",
    "waf/acl_name",
    "waf/log_bucket_arn",
  ]

  foundation     = { for k, p in data.aws_ssm_parameter.foundation : k => p.insecure_value }
  app_subnet_ids = split(",", local.foundation["network/app_subnet_ids"])
}

data "aws_ssm_parameter" "foundation" {
  for_each = toset(local.foundation_keys)

  name = "${local.ssm_prefix}/${each.key}"
}

# --- edge: REST stage → VPC Link v2 → internal ALB (D5) ----------------------------------------

module "alb" {
  source = "../../modules/alb"
  count  = var.enable_alb ? 1 : 0

  name              = "${local.name_prefix}-alb"
  subnet_ids        = local.app_subnet_ids
  security_group_id = local.foundation["network/sg/alb"]
}

module "rest_stage" {
  source = "../../modules/rest-stage"
  count  = var.enable_alb ? 1 : 0

  rest_api_id          = local.foundation["apigw/rest/id"]
  proxy_resource_id    = local.foundation["apigw/rest/proxy_resource_id"]
  proxy_http_method    = local.foundation["apigw/rest/proxy_http_method"]
  definition_hash      = local.foundation["apigw/rest/definition_hash"]
  stage_name           = local.foundation["apigw/rest/stage_name"]
  access_log_group_arn = local.foundation["apigw/rest/access_log_arn"]

  vpc_link_name              = "${local.name_prefix}-edge"
  subnet_ids                 = local.app_subnet_ids
  vpc_link_security_group_id = local.foundation["network/sg/vpclink"]
  alb_arn                    = module.alb[0].arn
  alb_dns_name               = module.alb[0].dns_name
}

# --- WAF on the stage (D6) ------------------------------------------------------------------------

module "waf" {
  source = "../../modules/waf"
  count  = var.enable_waf ? 1 : 0

  name           = local.foundation["waf/acl_name"]
  resource_arn   = module.rest_stage[0].stage_arn
  log_bucket_arn = local.foundation["waf/log_bucket_arn"]
}
