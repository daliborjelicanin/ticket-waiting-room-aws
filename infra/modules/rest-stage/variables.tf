variable "rest_api_id" {
  description = "Foundation's REST API (modules/rest-api)."
  type        = string
}

variable "proxy_resource_id" {
  description = "Foundation's /{proxy+} resource."
  type        = string
}

variable "proxy_http_method" {
  description = "Foundation's /{proxy+} method (ANY)."
  type        = string
}

variable "definition_hash" {
  description = "Foundation's published hash of everything a deployment snapshots; a change redeploys the stage."
  type        = string
}

variable "stage_name" {
  description = "Stage name. Fixed by foundation: the invoke URL every QR encodes contains it (D19)."
  type        = string
}

variable "access_log_group_arn" {
  description = "Access log group, declared in foundation with its retention."
  type        = string
}

variable "vpc_link_name" {
  description = "VPC link name, twr-<env>-edge (D25)."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the VPC link's ENIs: the app subnets, beside the ALB (D3)."
  type        = list(string)
}

variable "vpc_link_security_group_id" {
  description = "The VPC link's SG from modules/network: out to the ALB only."
  type        = string
}

variable "alb_arn" {
  description = "The internal ALB, the integration target (D5)."
  type        = string

  validation {
    condition     = can(regex("^arn:[^:]+:elasticloadbalancing:[^:]+:[^:]+:loadbalancer/app/", var.alb_arn))
    error_message = "The target must be an Application Load Balancer ARN."
  }
}

variable "alb_dns_name" {
  description = "The internal ALB's DNS name, the host in the integration URI."
  type        = string
}
