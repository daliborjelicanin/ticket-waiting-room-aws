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

variable "cost_allocation_tag_keys" {
  description = "Tag keys activated for cost allocation (D17). Add stack and env once Billing lists them (2026-11-15: only project)."
  type        = set(string)
  default     = ["project"]

  validation {
    condition     = length(setsubtract(var.cost_allocation_tag_keys, ["project", "stack", "env"])) == 0
    error_message = "Only the default_tags keys project, stack and env are cost-allocation tags."
  }
}
