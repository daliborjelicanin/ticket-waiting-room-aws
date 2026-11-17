variable "name" {
  description = "Web ACL name, twr-<env>-edge: foundation's dashboard reads its metrics by this name (D25)."
  type        = string
}

variable "resource_arn" {
  description = "The REST API stage the ACL protects."
  type        = string

  validation {
    condition     = can(regex("^arn:[^:]+:apigateway:[^:]+::/restapis/[^/]+/stages/[^/]+$", var.resource_arn))
    error_message = "The ACL attaches to a REST API stage ARN (D5: REGIONAL REST, never edge-optimised)."
  }
}

variable "log_bucket_arn" {
  description = "Foundation's WAF log bucket. WAF only delivers to a bucket named aws-waf-logs-*."
  type        = string

  validation {
    condition     = can(regex("^arn:[^:]+:s3:::aws-waf-logs-", var.log_bucket_arn))
    error_message = "WAF logs only to a bucket whose name starts with aws-waf-logs-."
  }
}

variable "fleet_guard_limit" {
  description = "Requests per JA4 fingerprint per 60 s before the fleet guard matches (D6)."
  type        = number
  default     = 300

  validation {
    condition     = var.fleet_guard_limit >= 10
    error_message = "WAF's minimum rate limit is 10."
  }
}
