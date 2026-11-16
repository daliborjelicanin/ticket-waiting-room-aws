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

variable "repository" {
  description = "GitHub owner/name; alarm descriptions link to docs/runbook-alarms.md there."
  type        = string
}

variable "log_groups" {
  description = "Log groups this module declares, by key: \"ecs/<service>\" per ECS service, \"metrics\" (collector EMF) and \"saga\". The names are decided in the root, where the IAM roles take them too."
  type        = map(string)

  validation {
    condition     = contains(keys(var.log_groups), "metrics") && contains(keys(var.log_groups), "ecs/waiting-room")
    error_message = "log_groups needs \"metrics\" (the collector writes there) and \"ecs/waiting-room\" (a saved query reads it)."
  }
}

variable "log_retention_days" {
  description = "Retention of the service, metrics and saga log groups."
  type        = number
  default     = 3
}

variable "ecs_exec_log_group" {
  description = "ECS Exec session audit log group (D12)."
  type        = string
}

variable "audit_log_retention_days" {
  description = "Retention of the ECS Exec audit log."
  type        = number
  default     = 30
}

variable "collector_config_parameter" {
  description = "SSM parameter the collector sidecar reads its configuration from (the execution roles may read it)."
  type        = string
}

variable "metrics_namespace" {
  description = "CloudWatch namespace of the services' EMF metrics."
  type        = string
  default     = "TicketWaitingRoom"
}

variable "queue_names" {
  description = "Main queue names by short name (modules/messaging queue_names)."
  type        = map(string)
}

variable "visibility_timeouts" {
  description = "Visibility timeout by queue short name, seconds (modules/messaging visibility_timeouts)."
  type        = map(number)
}

variable "queue_age_multiplier" {
  description = "A queue is stuck when its oldest message is older than this many visibility timeouts (D16)."
  type        = number
  default     = 3
}

variable "dlq_names" {
  description = "Every DLQ name, by key (modules/messaging dlq_names)."
  type        = map(string)
}

variable "lambda_function_names" {
  description = "Function names by key."
  type        = map(string)
}

variable "lambda_log_groups" {
  description = "Lambda log groups by function key (declared by modules/lambda-function), for the saved queries."
  type        = map(string)
}

variable "websocket_api_id" {
  description = "WebSocket API id, for the dashboard."
  type        = string
}

variable "websocket_stage_name" {
  description = "WebSocket stage name, for the dashboard."
  type        = string
}

variable "websocket_access_log_group" {
  description = "WebSocket access log group, for the saved queries."
  type        = string
}

variable "saga_state_machine_arn" {
  description = "The saga's ARN (deterministic, D25), for the dashboard; the state machine itself is the session stack's."
  type        = string
}

variable "seat_table_name" {
  description = "Seat table name, for the dashboard's conditional-check widget."
  type        = string
}

variable "waf_acl_name" {
  description = "The session stack's web ACL name (deterministic, D25), for the dashboard."
  type        = string
}
