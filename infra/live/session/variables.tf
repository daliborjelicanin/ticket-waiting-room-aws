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

variable "enable_alb" {
  description = "Internal ALB, VPC Link v2, the /{proxy+} private integration, deployment and stage (D5). Off = the REST API has no stage."
  type        = bool
  default     = false
}

variable "enable_waf" {
  description = "Web ACL on the REST stage, with logging (D6). Needs enable_alb: the ACL attaches to the stage."
  type        = bool
  default     = false

  validation {
    condition     = !var.enable_waf || var.enable_alb
    error_message = "enable_waf needs enable_alb: without the ALB there is no stage to attach the ACL to."
  }
}

variable "enable_nat" {
  description = "NAT gateway and the app tier's default routes (D4). Needed by anything that starts an ECS task; not by the edge alone."
  type        = bool
  default     = false
}

variable "nat_mode" {
  description = "zonal (sessions: one gateway in AZ a) or regional (the production shape, D4)."
  type        = string
  default     = "zonal"

  validation {
    condition     = contains(["zonal", "regional"], var.nat_mode)
    error_message = "nat_mode is zonal or regional."
  }
}

variable "waf_baseline_mode" {
  description = "AWS managed baseline on the whole stage: count until its sampled requests (the saga's included, slice 3) are reviewed, then block (D5, D6)."
  type        = string
  default     = "count"

  validation {
    condition     = contains(["count", "block"], var.waf_baseline_mode)
    error_message = "waf_baseline_mode is count or block."
  }
}

variable "loadtest_allow_cidr" {
  description = "The load test runner's address (a.b.c.d/32), allowed ahead of the buyer rules for the concurrency proof only. Pass with -var at apply time; never committed, never in a tfvars file (D6, CLAUDE.md Secrets)."
  type        = string
  default     = null
  sensitive   = true
}
