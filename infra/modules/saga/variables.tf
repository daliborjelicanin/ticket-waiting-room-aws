variable "name_prefix" {
  description = "twr-<env>. The connection is named <prefix>-saga: the saga role may retrieve credentials only from connections under this prefix (modules/iam integrations.tf)."
  type        = string

  validation {
    condition     = can(regex("^twr-[a-z0-9]+$", var.name_prefix))
    error_message = "twr-<env>, lowercase."
  }
}

variable "token_url" {
  description = "The Cognito domain's OAuth token endpoint (foundation's cognito/token_url)."
  type        = string

  validation {
    condition     = can(regex("^https://[^/]+/oauth2/token$", var.token_url))
    error_message = "An https Cognito token endpoint ending in /oauth2/token."
  }
}

variable "client_id" {
  description = "The ops app client (foundation's cognito/ops_client_id): confidential, client_credentials only."
  type        = string
}

variable "client_secret" {
  description = "The ops client's secret, read in the session root from the client itself. The one secret D13 accepts in state; never typed, never printed."
  type        = string
  sensitive   = true
}

variable "scope" {
  description = "The one scope the saga asks for. The services' operator paths require it (security-starter EndpointAuthorization)."
  type        = string
  default     = "ticketing/ops"

  validation {
    condition     = var.scope == "ticketing/ops"
    error_message = "The saga is the ops client: ticketing/ops and nothing wider (D12)."
  }
}

variable "definition" {
  description = "The saga's Amazon States Language, as written in docs/checkout-saga.asl.json: JSON with the __CONNECTION_ARN__, __QUEUE_URL__ and __TOPIC_ARN__ placeholders that the contract tests read the same file around."
  type        = string

  validation {
    condition = (
      can(jsondecode(var.definition))
      && strcontains(var.definition, "__CONNECTION_ARN__")
      && strcontains(var.definition, "__QUEUE_URL__")
      && strcontains(var.definition, "__TOPIC_ARN__")
    )
    error_message = "JSON carrying the three placeholders: is this docs/checkout-saga.asl.json?"
  }
}

variable "role_arn" {
  description = "Foundation's saga role (modules/iam): the HTTP tasks, the connection, SQS, SNS, X-Ray, log delivery, and the key for its own execution data."
  type        = string
}

variable "issuance_queue_url" {
  description = "The ticket-issuance queue the saga enqueues to after a confirmed purchase."
  type        = string
}

variable "purchase_confirmed_topic_arn" {
  description = "The purchase-confirmed topic the saga notifies on (the buyer's email goes to notification-service through it)."
  type        = string
}

variable "kms_key_arn" {
  description = "The CMK (D11): execution input and history carry the buyer's email."
  type        = string
}

variable "log_group_arn" {
  description = "Foundation's /aws/vendedlogs/states/<prefix>-checkout-saga group (D25)."
  type        = string
}
