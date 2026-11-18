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

variable "queue_resource_id" {
  description = "Foundation's /events/{eventId}/queue resource (queue join and the position poll)."
  type        = string
}

variable "queue_http_methods" {
  description = "The methods foundation declares on the queue resource; each gets the private integration."
  type        = list(string)
  default     = ["GET", "POST"]
}

variable "queue_path" {
  description = "Resource path of queue join, /events/{eventId}/queue, as foundation publishes it."
  type        = string

  validation {
    condition     = strcontains(var.queue_path, "{eventId}") && startswith(var.queue_path, "/")
    error_message = "The queue path is an absolute resource path carrying {eventId}."
  }
}

variable "purchase_path" {
  description = "Resource path of purchase, /events/{eventId}/purchase, as foundation publishes it."
  type        = string

  validation {
    condition     = startswith(var.purchase_path, "/")
    error_message = "The purchase path is an absolute resource path."
  }
}

variable "throttle_rate" {
  description = "Steady requests per second per buyer entry point, stage-wide (D6). Keys: queue, purchase."
  type        = map(number)
  default = {
    queue    = 500
    purchase = 100
  }

  validation {
    condition     = length(setsubtract(["queue", "purchase"], keys(var.throttle_rate))) == 0
    error_message = "throttle_rate needs a value for queue and for purchase."
  }
}

variable "throttle_burst" {
  description = "Burst per buyer entry point (D6). Keys: queue, purchase."
  type        = map(number)
  default = {
    queue    = 1000
    purchase = 200
  }

  validation {
    condition     = length(setsubtract(["queue", "purchase"], keys(var.throttle_burst))) == 0
    error_message = "throttle_burst needs a value for queue and for purchase."
  }
}
