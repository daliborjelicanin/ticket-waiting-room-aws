# The stack interface (D2): foundation publishes what session needs as SSM parameters under
# /twr/<env>/foundation/, and session reads them with data sources. No terraform_remote_state:
# session never sees foundation's state, and Phase 3's EKS root reads the same parameters.
# Lists are comma-joined StringList values; nothing here is secret.

locals {
  ssm_prefix = "/twr/${var.env}/foundation"

  published = merge(
    {
      "network/vpc_id"               = module.network.vpc_id
      "network/vpc_cidr"             = module.network.vpc_cidr
      "network/app_subnet_ids"       = join(",", module.network.app_subnet_ids)
      "network/public_subnet_ids"    = join(",", module.network.public_subnet_ids)
      "network/db_subnet_ids"        = join(",", module.network.db_subnet_ids)
      "network/db_subnet_group_name" = module.network.db_subnet_group_name
      "network/app_route_table_ids"  = join(",", module.network.app_route_table_ids)
      "kms/key_arn"                  = module.kms.key_arn
      "observability/ops_alarms_arn" = module.observability.ops_alarms_topic_arn
    },
    { for k, id in module.network.security_group_ids : "network/sg/${k}" => id },
  )
}

resource "aws_ssm_parameter" "published" {
  for_each = local.published

  name  = "${local.ssm_prefix}/${each.key}"
  type  = can(regex(",", each.value)) ? "StringList" : "String"
  value = each.value
}
