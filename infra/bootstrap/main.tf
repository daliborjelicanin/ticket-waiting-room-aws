# twr-dev, applied once by the owner with the admin permission set (D14):
#
#   source infra/scripts/tf-env.sh && terraform -chdir=infra/bootstrap apply
#
# Creates what CI needs before it can apply anything: the state bucket (state.tf), the GitHub
# OIDC provider and the four roles (oidc.tf, roles.tf), and the permissions boundary every
# role foundation creates must carry (boundary.tf). First applied with local state, then
# migrated into the bucket it creates (README.md).

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  prefix     = "twr-${var.env}"

  state_bucket = "${local.prefix}-tfstate-${local.account_id}"

  # Roles created here. Foundation may create other twr-dev-* roles, never touch these (roles.tf).
  ci_role_names = {
    images        = "${local.prefix}-ci-images"
    plan          = "${local.prefix}-tf-plan"
    apply_found   = "${local.prefix}-tf-apply-foundation"
    apply_session = "${local.prefix}-tf-apply-session"
  }
  boundary_name = "${local.prefix}-boundary"

  # Principals that may use the state bucket (D11): the three Terraform roles, the owner's
  # admin permission set, and the management account's break-glass role.
  state_principals = [
    "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/${local.ci_role_names.plan}",
    "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/${local.ci_role_names.apply_found}",
    "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/${local.ci_role_names.apply_session}",
    "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/aws-reserved/sso.amazonaws.com/*/AWSReservedSSO_AdministratorAccess_*",
    "arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/OrganizationAccountAccessRole",
  ]
}
