variable "account_id" {
  description = "The only account this root may touch (provider allowed_account_ids). Supplied at run time by infra/scripts/tf-env.sh or a masked CI variable, never committed (CLAUDE.md, Secrets and Identifiers)."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "account_id must be a 12-digit AWS account id."
  }
}

variable "region" {
  description = "Home region."
  type        = string
  default     = "eu-central-1"
}

variable "env" {
  description = "Environment name, the middle part of every resource name (twr-<env>-..., D25)."
  type        = string
  default     = "dev"
}

variable "github_repository" {
  description = "owner/name of the repository whose workflows may assume the CI roles (D14). Public, so committable."
  type        = string
  default     = "daliborjelicanin/ticket-waiting-room-aws"
}

variable "github_oidc_sub_prefix" {
  description = "The repository's OIDC sub prefix, as GET /repos/{repo}/actions/oidc/customization/sub returns it. Immutable form: owner and repo numeric ids are included, so a deleted-and-recreated repository with the same name cannot assume these roles (D14). Public GitHub ids, not AWS identifiers."
  type        = string
  default     = "repo:daliborjelicanin@67155304/ticket-waiting-room-aws@1411760078"
}
