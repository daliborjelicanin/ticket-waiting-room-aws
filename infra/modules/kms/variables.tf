variable "name" {
  description = "Alias name without the alias/ prefix, e.g. twr-dev."
  type        = string
}

variable "alarm_publishers" {
  description = "Service principals that publish to the CMK-encrypted ops-alarms topic and so need GenerateDataKey + Decrypt on this key (D11, D16)."
  type        = list(string)
  default = [
    "budgets.amazonaws.com",
    "cloudwatch.amazonaws.com",
    "costalerts.amazonaws.com",
    "events.amazonaws.com",
  ]
}
