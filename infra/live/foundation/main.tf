# Standing resources (§2 "What lives where"). Slice 0 is the skeleton every later alert needs:
# the CMK, the ops-alarms topic, the budgets, and the audit trail. Slice 1 adds the rest.

data "aws_iam_policy" "boundary" {
  name = "${local.name_prefix}-boundary" # created by infra/bootstrap (D14)
}

locals {
  name_prefix = "twr-${var.env}"
}

module "kms" {
  source = "../../modules/kms"

  name = local.name_prefix
}

module "observability" {
  source = "../../modules/observability"

  name_prefix = local.name_prefix
  kms_key_arn = module.kms.key_arn
  alert_email = var.alert_email
}

module "budgets" {
  source = "../../modules/budgets"

  name_prefix          = local.name_prefix
  alert_email          = var.alert_email
  ops_alarms_topic_arn = module.observability.ops_alarms_topic_arn
  phase_start          = var.phase2_start
  phase_end            = var.phase2_end
}

module "audit" {
  source = "../../modules/audit"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name_prefix              = local.name_prefix
  ops_alarms_topic_arn     = module.observability.ops_alarms_topic_arn
  permissions_boundary_arn = data.aws_iam_policy.boundary.arn
}

module "network" {
  source = "../../modules/network"

  name_prefix = local.name_prefix
}
