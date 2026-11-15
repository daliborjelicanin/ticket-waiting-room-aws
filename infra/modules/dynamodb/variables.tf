variable "name" {
  description = "Table name, twr-<env>-<name> (D25)."
  type        = string
}

variable "hash_key" {
  description = "Partition key attribute (type S)."
  type        = string
}

variable "range_key" {
  description = "Sort key attribute (type S), or null."
  type        = string
  default     = null
}

variable "global_secondary_indexes" {
  description = "GSIs. Key attributes are type S; keep the projection minimal, every GSI doubles a write."
  type = list(object({
    name            = string
    hash_key        = string
    range_key       = optional(string)
    projection_type = optional(string, "KEYS_ONLY")
  }))
  default = []
}

variable "ttl_attribute" {
  description = "TTL attribute (epoch seconds, Number). Required: both project tables rely on TTL (retro §3)."
  type        = string
}

variable "point_in_time_recovery" {
  description = "Continuous backups (D24)."
  type        = bool
  default     = true
}

variable "pitr_recovery_days" {
  description = "PITR window in days (D24)."
  type        = number
  default     = 7

  validation {
    condition     = var.pitr_recovery_days >= 1 && var.pitr_recovery_days <= 35
    error_message = "PITR recovery period is 1 to 35 days."
  }
}

variable "deletion_protection" {
  description = "AWS-side deletion protection (D24). Set false, apply, then destroy at the end of the phase."
  type        = bool
  default     = true
}
