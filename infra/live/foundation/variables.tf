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

variable "alert_email" {
  description = "Owner's address for ops-alarms and budgets (committable: CLAUDE.md, Secrets and Identifiers)."
  type        = string
  default     = "jelicanindalibor@gmail.com"
}

variable "phase2_start" {
  description = "First day of the Phase 2 cumulative budget (D17)."
  type        = string
  default     = "2026-11-01"
}

variable "phase2_end" {
  description = "Last day of the Phase 2 cumulative budget (D17); moved if the phase runs longer."
  type        = string
  default     = "2027-04-30"
}
