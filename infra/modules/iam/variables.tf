variable "name_prefix" {
  description = "Role name prefix, twr-<env> (D25). The boundary lets roles pass only <prefix>-* roles."
  type        = string
}

variable "permissions_boundary_arn" {
  description = "The boundary every role carries (bootstrap, D12, D14)."
  type        = string
}

variable "kms_key_arn" {
  description = "The CMK; granted per role with kms:ViaService (D11)."
  type        = string
}

variable "seat_table_arn" {
  description = "Seat inventory table ARN."
  type        = string
}

variable "connections_table_arn" {
  description = "WebSocket connection registry table ARN."
  type        = string
}

variable "queue_arns" {
  description = "Main queue ARNs by short name (modules/messaging)."
  type        = map(string)
}

variable "topic_arns" {
  description = "Topic ARNs by short name (modules/messaging)."
  type        = map(string)
}

variable "qr_bucket_arn" {
  description = "QR bucket ARN."
  type        = string
}

variable "repository_arns" {
  description = "ECR repository ARNs by Gradle module name."
  type        = map(string)
}

variable "log_groups" {
  description = "Log group names the roles write to; the modules that create the groups take the same names."
  type = object({
    ecs      = map(string) # by ECS service key
    lambda   = map(string) # by function key
    apigw    = map(string) # API Gateway access logs, by API key
    metrics  = string      # collector EMF output
    ecs_exec = string      # ECS Exec session audit
    saga     = string      # Step Functions execution logs
  })
}

variable "lambda_function_names" {
  description = "Function names by key; the scheduler role may invoke hold-cleanup only."
  type        = map(string)
}

variable "collector_config_parameter" {
  description = "SSM parameter name holding the collector config, read by the execution roles."
  type        = string
}

variable "db" {
  description = "Names the session stack gives the database pieces, so their secrets can be granted before they exist."
  type = object({
    instance_identifier  = string
    service_secret_names = map(string) # checkout, ticketing
  })
}

variable "saga_state_machine_name" {
  description = "The checkout saga's state machine name (session stack)."
  type        = string
}

variable "ses_from_address" {
  description = "The one address notification-service may send as."
  type        = string
}

variable "websocket_api_execution_arn" {
  description = "Execution ARN of the WebSocket API, for the notifier's ManageConnections. null until that API exists."
  type        = string
  default     = null
}
