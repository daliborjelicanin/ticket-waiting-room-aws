variable "name_prefix" {
  description = "Resource name prefix, twr-<env> (D25)."
  type        = string

  validation {
    condition     = length(var.name_prefix) <= 12
    error_message = "Keep the prefix short: ALB and target-group names are limited to 32 characters (D25)."
  }
}

variable "cidr" {
  description = "VPC CIDR (D3). 10.20.0.0/16 avoids the default 10.0/16 and common home/VPN ranges."
  type        = string
  default     = "10.20.0.0/16"
}

variable "azs" {
  description = "The three availability zones, in subnet order (D3)."
  type        = list(string)
  default     = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
}

variable "public_subnets" {
  description = "Public /24s: the session NAT Gateway only (D3, D4)."
  type        = list(string)
  default     = ["10.20.0.0/24", "10.20.1.0/24", "10.20.2.0/24"]
}

variable "db_subnets" {
  description = "Isolated /24s with no route out: RDS and Valkey (D3)."
  type        = list(string)
  default     = ["10.20.4.0/24", "10.20.5.0/24", "10.20.6.0/24"]
}

variable "app_subnets" {
  description = "Private /20s: ALB nodes, VPC link ENIs, tasks, Phase 3 nodes and pods (D3, D21). 10.20.64.0/18 and up stays reserved for Phase 3."
  type        = list(string)
  default     = ["10.20.16.0/20", "10.20.32.0/20", "10.20.48.0/20"]
}

variable "flow_log_retention_days" {
  description = "Days VPC flow log files are kept (D3)."
  type        = number
  default     = 7
}

variable "service_port" {
  description = "Port every HTTP service listens on (each Dockerfile EXPOSEs 8080)."
  type        = number
  default     = 8080
}
