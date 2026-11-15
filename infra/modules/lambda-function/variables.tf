variable "function_name" {
  description = "Function name, twr-<env>-<name> (D25)."
  type        = string

  validation {
    condition     = length(var.function_name) <= 64
    error_message = "Lambda function names are at most 64 characters."
  }
}

variable "role_arn" {
  description = "Execution role, from modules/iam (D12)."
  type        = string
}

variable "image_uri" {
  description = "<repository-url>@sha256:<digest>. By digest, never by tag (D15)."
  type        = string

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image_uri))
    error_message = "image_uri must pin a digest (@sha256:...), not a tag."
  }
}

variable "memory_size" {
  description = "MB; Lambda scales CPU with it."
  type        = number
  default     = 1024
}

variable "timeout" {
  description = "Seconds. A queue consumer's visibility timeout is derived from it (modules/messaging)."
  type        = number

  validation {
    condition     = var.timeout >= 1 && var.timeout <= 900
    error_message = "Lambda timeout is 1 to 900 seconds."
  }
}

variable "environment" {
  description = "Environment variables: names and references only, never a typed value (D25)."
  type        = map(string)
  default     = {}
}

variable "log_group_name" {
  description = "Log group the function writes to; its role may write only there (modules/iam)."
  type        = string
}

variable "log_retention_days" {
  description = "Log retention (Phase 1: 3 days)."
  type        = number
  default     = 3
}

variable "sqs_event_source" {
  description = "Queue this function consumes, or null. Batch 10 and no batching window, as in Phase 1."
  type = object({
    queue_arn               = string
    batch_size              = optional(number, 10)
    batching_window_seconds = optional(number, 0)
  })
  default = null
}
