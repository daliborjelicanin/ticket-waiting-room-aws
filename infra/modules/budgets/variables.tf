variable "name_prefix" {
  description = "Resource name prefix, twr-<env> (D25)."
  type        = string
}

variable "monthly_limit_usd" {
  description = "Monthly budget (D17)."
  type        = number
  default     = 10
}

variable "phase_limit_usd" {
  description = "Cumulative budget for the whole of Phase 2 (D17)."
  type        = number
  default     = 20
}

variable "phase_start" {
  description = "First day of the Phase 2 budget period, YYYY-MM-DD."
  type        = string
}

variable "phase_end" {
  description = "Last day of the Phase 2 budget period, YYYY-MM-DD."
  type        = string
}

variable "alert_email" {
  description = "Owner's address, subscribed directly so the slowest guard does not depend on SNS, its CMK grant and a confirmed subscription (D17)."
  type        = string
}

variable "ops_alarms_topic_arn" {
  description = "ops-alarms topic, the second recipient of every budget and anomaly alert."
  type        = string
}

variable "anomaly_threshold_usd" {
  description = "Absolute impact above which a cost anomaly is reported."
  type        = number
  default     = 1
}
