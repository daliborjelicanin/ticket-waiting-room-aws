# The stack interface (D2): foundation publishes what session needs as SSM parameters under
# /twr/<env>/foundation/, and session reads them with data sources. No terraform_remote_state:
# session never sees foundation's state, and Phase 3's EKS root reads the same parameters.
# Nothing here is secret.
#
# The type is declared by which map a value is in, never inferred from its content: a list
# that shrank to one element must stay a StringList, or the parameter is replaced under its
# readers.

locals {
  ssm_prefix = "/twr/${var.env}/foundation"

  published_strings = merge(
    {
      "network/vpc_id"               = module.network.vpc_id
      "network/vpc_cidr"             = module.network.vpc_cidr
      "network/db_subnet_group_name" = module.network.db_subnet_group_name
      "kms/key_arn"                  = module.kms.key_arn
      "observability/ops_alarms_arn" = module.observability.ops_alarms_topic_arn
      "dynamodb/seat_table_name"     = module.seat_table.name
      "dynamodb/seat_table_arn"      = module.seat_table.arn
      "dynamodb/connections_name"    = module.connections_table.name
      "dynamodb/connections_arn"     = module.connections_table.arn
      "s3/qr_bucket_name"            = module.qr_bucket.name
      "cognito/issuer_uri"           = module.identity.issuer_uri
      "cognito/token_url"            = module.identity.token_url
      "cognito/user_pool_id"         = module.identity.user_pool_id
      "cognito/web_client_id"        = module.identity.web_client_id
      "ses/from_address"             = local.ses_sender
      "ses/fallback_recipient"       = local.ses_recipients[0]
    },
    { for k, id in module.identity.m2m_client_ids : "cognito/${k}_client_id" => id },
    { for k, arn in module.iam.role_arns : "iam/role/${k}" => arn },
    {
      "lambda/hold-cleanup/arn"        = module.hold_cleanup.function_arn
      "lambda/qr-generation/arn"       = module.qr_generation.function_arn
      "lambda/queue-notifier/arn"      = module.queue_notifier.function_arn
      "apigw/rest/id"                  = module.rest_api.rest_api_id
      "apigw/rest/root_resource_id"    = module.rest_api.root_resource_id
      "apigw/rest/proxy_resource_id"   = module.rest_api.proxy_resource_id
      "apigw/rest/proxy_http_method"   = module.rest_api.proxy_http_method
      "apigw/rest/execution_arn"       = module.rest_api.execution_arn
      "apigw/rest/invoke_url"          = module.rest_api.invoke_url
      "apigw/rest/definition_hash"     = module.rest_api.definition_hash
      "apigw/rest/access_log_arn"      = module.rest_api.access_log_group_arn
      "apigw/rest/stage_name"          = local.api_stage_name
      "apigw/websocket/client_url"     = module.websocket_api.client_url
      "apigw/websocket/management_url" = module.websocket_api.management_endpoint
      "waf/acl_name"                   = local.waf_acl_name
      "waf/log_bucket_arn"             = module.waf_log_bucket.arn
    },
    { for k, id in module.network.security_group_ids : "network/sg/${k}" => id },
    { for k, url in module.ecr.repository_urls : "ecr/${k}" => url },
    { for k, url in module.messaging.queue_urls : "messaging/queue/${k}/url" => url },
    { for k, arn in module.messaging.queue_arns : "messaging/queue/${k}/arn" => arn },
    { for k, arn in module.messaging.topic_arns : "messaging/topic/${k}/arn" => arn },
  )

  published_lists = {
    "network/app_subnet_ids"      = module.network.app_subnet_ids
    "network/public_subnet_ids"   = module.network.public_subnet_ids
    "network/db_subnet_ids"       = module.network.db_subnet_ids
    "network/app_route_table_ids" = module.network.app_route_table_ids
  }

  published = merge(
    local.published_strings,
    { for k, v in local.published_lists : k => join(",", v) },
  )
}

resource "aws_ssm_parameter" "published" {
  for_each = local.published

  name  = "${local.ssm_prefix}/${each.key}"
  type  = contains(keys(local.published_lists), each.key) ? "StringList" : "String"
  value = each.value
}
