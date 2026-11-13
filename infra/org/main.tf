# Management account only (D12). Applied by hand with the admin permission set:
#
#   source infra/scripts/tf-env.sh default && terraform -chdir=infra/org apply
#
# Holds what the management account owns for the whole organization: who can sign in where
# (sso.tf), what any principal in the workloads OU may never do (scp.tf), a budget on the
# management account's own spend (budget.tf), and this root's state bucket (state.tf).
# Nothing here is a workload, and no CI role can reach this account.

data "aws_caller_identity" "current" {}

data "aws_organizations_organization" "this" {}

data "aws_organizations_organizational_units" "root" {
  parent_id = data.aws_organizations_organization.this.roots[0].id
}

data "aws_ssoadmin_instances" "this" {}

locals {
  management_account_id = data.aws_caller_identity.current.account_id

  # Looked up by name, never typed (CLAUDE.md, Secrets and Identifiers).
  twr_dev_account_id = one([for a in data.aws_organizations_organization.this.accounts : a.id if a.name == "twr-dev"])
  workloads_ou_id    = one([for ou in data.aws_organizations_organizational_units.root.children : ou.id if ou.name == "workloads"])

  sso_instance_arn  = tolist(data.aws_ssoadmin_instances.this.arns)[0]
  identity_store_id = tolist(data.aws_ssoadmin_instances.this.identity_store_ids)[0]

  # The person applying this root is the owner: the last segment of an SSO session ARN is the
  # Identity Center user name. Avoids committing the user name or passing it in.
  owner_user_name = element(split("/", data.aws_caller_identity.current.arn), 2)
}

data "aws_identitystore_user" "owner" {
  identity_store_id = local.identity_store_id

  alternate_identifier {
    unique_attribute {
      attribute_path  = "UserName"
      attribute_value = local.owner_user_name
    }
  }
}
