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
  description = "Environment tag for this root. The management account is not an environment, so it is tagged \"org\"."
  type        = string
  default     = "org"
}

variable "alert_email" {
  description = "Owner's address for the management-account budget alert (committable: CLAUDE.md, Secrets and Identifiers)."
  type        = string
  default     = "jelicanindalibor@gmail.com"
}

variable "activate_cost_allocation_tags" {
  description = "Set true once project/stack/env appear in Billing > Cost allocation tags (about 24 h after twr-dev's first tagged resources)."
  type        = bool
  default     = false
}
