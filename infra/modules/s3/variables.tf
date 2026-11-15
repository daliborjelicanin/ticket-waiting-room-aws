variable "name" {
  description = "Bucket name without the account-id suffix, twr-<env>-<name> (D25)."
  type        = string

  validation {
    condition     = length(var.name) <= 50
    error_message = "Bucket names are at most 63 characters, and the suffix adds 13."
  }
}

variable "kms_key_arn" {
  description = "CMK for SSE-KMS (D11: customer data or credentials). null = SSE-S3."
  type        = string
  default     = null
}

variable "expiration_days" {
  description = "Delete objects after this many days. null = keep."
  type        = number
  default     = null
}

variable "force_destroy" {
  description = "Let destroy empty the bucket. Only for contents that are rebuilt or disposable."
  type        = bool
  default     = false
}

variable "extra_policy_json" {
  description = "Further bucket-policy statements, merged with the TLS-only deny."
  type        = string
  default     = null
}
