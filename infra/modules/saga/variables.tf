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
