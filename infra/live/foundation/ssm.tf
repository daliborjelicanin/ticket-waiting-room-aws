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
    { for k, v in local.published_strings : k => { type = "String", value = v } },
    { for k, v in local.published_lists : k => { type = "StringList", value = join(",", v) } },
  )
}

resource "aws_ssm_parameter" "published" {
  for_each = local.published

  name  = "${local.ssm_prefix}/${each.key}"
  type  = each.value.type
  value = each.value.value
}
