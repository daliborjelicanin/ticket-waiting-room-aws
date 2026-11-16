variable "name" {
  description = "API name, twr-<env>-queue-socket (D25)."
  type        = string
}

variable "stage_name" {
  description = "Stage name; part of both the client URL and the notifier's endpoint."
  type        = string
}

variable "connections_table_name" {
  description = "The connection registry the routes write (ConnectionTableSchema)."
  type        = string
}

variable "integration_role_arn" {
  description = "Role API Gateway assumes for the DynamoDB writes (modules/iam websocket-apigw)."
  type        = string
}

variable "connect_throttle" {
  description = "Rate (requests/s) and burst for $connect, and the default for every route (D18)."
  type = object({
    rate_limit  = number
    burst_limit = number
  })
  default = { rate_limit = 100, burst_limit = 200 }
}

variable "access_log_group_name" {
  description = "Access log group, declared here with its retention."
  type        = string
}

variable "log_retention_days" {
  description = "Access log retention."
  type        = number
  default     = 3
}

variable "account_logging_role_arn" {
  description = "API Gateway's account-level CloudWatch role, as the account setting reports it. Access logs are refused until it is set; passing it orders the stage after the setting."
  type        = string
}
