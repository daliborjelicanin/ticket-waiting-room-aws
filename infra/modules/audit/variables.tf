variable "name_prefix" {
  description = "Resource name prefix, twr-<env> (D25)."
  type        = string
}

variable "ops_alarms_topic_arn" {
  description = "Target of every security alert (D24, D27)."
  type        = string
}

variable "permissions_boundary_arn" {
  description = "Boundary every role foundation creates must carry (D14)."
  type        = string
}

variable "log_retention_days" {
  description = "Days CloudTrail log files are kept in S3 before expiry."
  type        = number
  default     = 90
}
