variable "name" {
  description = "Load balancer name, twr-<env>-alb (D25)."
  type        = string

  validation {
    condition     = length(var.name) <= 32
    error_message = "ALB names are at most 32 characters (D25)."
  }
}

variable "subnet_ids" {
  description = "The app subnets, one per AZ (D3)."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "An ALB needs subnets in at least two AZs."
  }
}

variable "security_group_id" {
  description = "The ALB's SG from modules/network: in from the VPC link SG only, out to the HTTP services only."
  type        = string
}

variable "idle_timeout_seconds" {
  description = "Connection idle timeout. Above API Gateway's 29 s integration timeout, so the edge always times out first."
  type        = number
  default     = 60

  validation {
    condition     = var.idle_timeout_seconds > 29
    error_message = "Keep the idle timeout above API Gateway's 29 s integration timeout."
  }
}

variable "name_prefix" {
  description = "twr-<env>; target groups are named <prefix>-<service>-tg (D25)."
  type        = string
}

variable "vpc_id" {
  description = "The VPC the target groups live in."
  type        = string
}

variable "service_port" {
  description = "Port every HTTP service listens on (each Dockerfile EXPOSEs 8080; the SG rules in modules/network allow it)."
  type        = number
  default     = 8080
}

variable "routes" {
  description = "Per service: listener rule priority and path patterns (ALB globs, * crosses /, at most 5). Only running services get a route; anything else gets the default NO_ROUTE 404."
  type = map(object({
    priority = number
    paths    = list(string)
  }))
  default = {}

  validation {
    condition     = alltrue([for k in keys(var.routes) : (length("${var.name_prefix}-${k}-tg") <= 32)])
    error_message = "Target group names (<prefix>-<service>-tg) are at most 32 characters (D25)."
  }

  validation {
    condition     = length(distinct([for r in values(var.routes) : r.priority])) == length(var.routes)
    error_message = "Each route needs its own priority: the ALB refuses two rules with one number."
  }

  validation {
    condition     = alltrue([for r in values(var.routes) : (length(r.paths) >= 1 && length(r.paths) <= 5)])
    error_message = "A listener rule takes 1 to 5 path patterns."
  }
}
