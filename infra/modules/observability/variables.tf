variable "name_prefix" {
  description = "Resource name prefix, twr-<env> (D25)."
  type        = string
}

variable "kms_key_arn" {
  description = "CMK that encrypts the ops-alarms topic (D11)."
  type        = string
}

variable "alert_email" {
  description = "Address subscribed to ops-alarms. The subscription must be confirmed by clicking the link AWS sends (D16)."
  type        = string
}
