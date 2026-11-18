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

variable "stage_name" {
  description = "The stage the ACL is on: WAF sees it as the first segment of the URI."
  type        = string
}

variable "buyer_entry_paths" {
  description = "Resource paths of the buyer entry points, as foundation publishes them (/events/{eventId}/queue, /events/{eventId}/purchase). Only POST to these gets the buyer rules (D6)."
  type        = list(string)

  validation {
    condition     = length(var.buyer_entry_paths) > 0 && alltrue([for p in var.buyer_entry_paths : startswith(p, "/")])
    error_message = "At least one absolute resource path."
  }
}

variable "baseline_mode" {
  description = "AWS managed baseline (IP reputation, known bad inputs, common rule set): count until its sampled requests, the saga's included, have been reviewed; then block (D5, D6)."
  type        = string
  default     = "count"

  validation {
    condition     = contains(["count", "block"], var.baseline_mode)
    error_message = "baseline_mode is count or block."
  }
}

variable "flood_guard_limit" {
  description = "Buyer-entry requests per IP per 60 s. Generous: CGNAT and venue Wi-Fi share one address (D6)."
  type        = number
  default     = 300

  validation {
    condition     = var.flood_guard_limit >= 10
    error_message = "WAF's minimum rate limit is 10."
  }
}

variable "credential_guard_limit" {
  description = "Buyer-entry requests per Authorization header per 60 s: faster than a human joins or buys (D6)."
  type        = number
  default     = 30

  validation {
    condition     = var.credential_guard_limit >= 10
    error_message = "WAF's minimum rate limit is 10."
  }
}

variable "loadtest_allow_cidr" {
  description = "The load test runner's address (a.b.c.d/32), allowed before the buyer rules for the concurrency proof only. Passed at apply time, never committed; null outside that run (D6)."
  type        = string
  default     = null
  sensitive   = true

  validation {
    condition     = var.loadtest_allow_cidr == null || can(cidrnetmask(var.loadtest_allow_cidr))
    error_message = "loadtest_allow_cidr is an IPv4 CIDR such as 203.0.113.7/32."
  }
}
