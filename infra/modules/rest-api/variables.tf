variable "name" {
  description = "API name, twr-<env>-edge (D25)."
  type        = string
}

variable "stage_name" {
  description = "The stage the session stack creates. Fixed, because the invoke URL (and every QR) contains it."
  type        = string
}

variable "user_pool_arn" {
  description = "Cognito user pool behind the purchase authorizer."
  type        = string
}

variable "saga_state_machine_arn" {
  description = "The checkout saga's ARN. Deterministic (D25), so it is known before the session stack creates it."
  type        = string
}

variable "start_execution_role_arn" {
  description = "Role API Gateway assumes to start the saga (modules/iam purchase-apigw)."
  type        = string
}

variable "access_log_group_name" {
  description = "Access log group for the session stack's stage, declared here with its retention."
  type        = string
}

variable "log_retention_days" {
  description = "Access log retention."
  type        = number
  default     = 3
}
