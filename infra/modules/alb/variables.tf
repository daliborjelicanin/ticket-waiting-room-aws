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
