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

variable "sqs_delivering_topics" {
  description = "Names of SNS topics that deliver to CMK-encrypted SQS queues; each needs GenerateDataKey + Decrypt on this key (D11)."
  type        = list(string)
  default     = []
}

variable "state_machine_log_delivery" {
  description = "A state machine on this key logs to CloudWatch (the checkout saga, D11, D16): CloudWatch's log delivery needs Decrypt on the key."
  type        = bool
  default     = false
}
