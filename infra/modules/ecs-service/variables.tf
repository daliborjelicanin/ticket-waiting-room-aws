variable "name" {
  description = "Service name and task definition family, twr-<env>-<service> (D25)."
  type        = string

  validation {
    condition     = can(regex("^[a-zA-Z0-9_-]{1,255}$", var.name))
    error_message = "Letters, digits, - and _ only, at most 255 characters (ECS)."
  }
}

variable "cluster_name" {
  description = "The session's cluster. Its name, not its ARN: the autoscaling resource id is service/<cluster>/<service>."
  type        = string
}

variable "container_name" {
  description = "The application container's name, also its log stream prefix."
  type        = string
}

variable "image_uri" {
  description = "<repository-url>@sha256:<digest>. By digest, never by tag (D15)."
  type        = string

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image_uri))
    error_message = "image_uri must pin a digest (@sha256:...), not a tag."
  }
}

variable "cpu" {
  description = "Task CPU units: 256 for feature slices, 512 for the proof (D7)."
  type        = number
  default     = 256

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096], var.cpu)
    error_message = "Fargate task CPU is 256, 512, 1024, 2048 or 4096."
  }
}

variable "memory" {
  description = "Task memory in MiB, shared by the application and the collector sidecar. 1024: with the sidecar's 256 MiB, 512 left the JVM too little (Phase 1, runbook-observability.md)."
  type        = number
  default     = 1024
}

variable "task_role_arn" {
  description = "The service's own identity, from modules/iam (D12)."
  type        = string
}

variable "execution_role_arn" {
  description = "What ECS uses to start the task (image, log stream, collector config, secrets), from modules/iam (D12)."
  type        = string
}

variable "subnet_ids" {
  description = "The app subnets (D3). Tasks get no public IP; egress is the NAT or the gateway endpoints (D4)."
  type        = list(string)
}

variable "security_group_id" {
  description = "The service's own SG from modules/network (D3)."
  type        = string
}

variable "container_port" {
  description = "Port the application listens on (each Dockerfile EXPOSEs 8080)."
  type        = number
  default     = 8080
}

variable "environment" {
  description = "Environment variables: names and references from module outputs, never a typed value (D21, D25). TELEMETRY_EXPORT_ENABLED and OTLP_ENDPOINT are added when the collector runs."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variables resolved by ECS at start: name => Secrets Manager or SSM ARN (D13). The execution role must be allowed to read each one."
  type        = map(string)
  default     = {}
}

variable "log_group_name" {
  description = "The service's log group, declared in foundation (modules/observability). The execution role may write only there."
  type        = string
}

variable "region" {
  description = "Region of the log group (awslogs-region)."
  type        = string
}

variable "collector_config_parameter" {
  description = "SSM parameter name holding the collector configuration (modules/observability), or null for no sidecar. The name, not the ARN: the parameter is in the task's region."
  type        = string
  default     = null
}

variable "collector_image" {
  description = "ADOT collector image, through the registry's pull-through cache (modules/ecr mirror_urls) so its layers skip the NAT. A version tag, never latest. Required with collector_config_parameter."
  type        = string
  default     = null

  validation {
    condition     = var.collector_config_parameter == null || var.collector_image != null
    error_message = "A collector needs its image: the pull-through cache URL with a version tag."
  }

  validation {
    condition     = var.collector_image == null || (!endswith(coalesce(var.collector_image, "x"), ":latest") && can(regex(":[^/]+$", coalesce(var.collector_image, "x"))))
    error_message = "Pin the collector image to a version tag, never latest."
  }

  validation {
    condition     = var.collector_image == null || !startswith(coalesce(var.collector_image, "x"), "public.ecr.aws/")
    error_message = "Not from public.ecr.aws directly: its layers come through the NAT. Use the pull-through cache (modules/ecr)."
  }
}

variable "collector_memory" {
  description = "MiB reserved for the collector out of the task's memory (Phase 1: 256 hard)."
  type        = number
  default     = 256
}

variable "stop_timeout" {
  description = "Seconds ECS waits after SIGTERM before SIGKILL. Must exceed Boot's 30 s timeout-per-shutdown-phase (observability-starter), or in-flight requests are killed mid-shutdown (D7)."
  type        = number
  default     = 45

  validation {
    condition     = var.stop_timeout > 30 && var.stop_timeout <= 120
    error_message = "stop_timeout must be above Boot's 30 s shutdown phase and at most 120 s (Fargate's limit, and Spot's two-minute notice)."
  }
}

variable "capacity" {
  description = "FARGATE_SPOT: every task on Spot (dev sessions). FARGATE: the D7 production mix, one on-demand task (base 1) that is never interrupted and Spot for everything above it."
  type        = string
  default     = "FARGATE_SPOT"

  validation {
    condition     = contains(["FARGATE_SPOT", "FARGATE"], var.capacity)
    error_message = "capacity is FARGATE_SPOT or FARGATE."
  }
}

variable "desired_count" {
  description = "Tasks at start, and the autoscaling minimum."
  type        = number
  default     = 1

  validation {
    condition     = var.desired_count >= 0
    error_message = "desired_count cannot be negative."
  }
}

variable "max_capacity" {
  description = "Autoscaling maximum (D7: 4)."
  type        = number
  default     = 4
}

variable "load_balancer" {
  description = "Target group to register into, from modules/alb, with the ALB's ARN suffix for the request-count scaling metric. null for a service with no HTTP traffic (notification)."
  type = object({
    target_group_arn        = string
    target_group_arn_suffix = string
    alb_arn_suffix          = string
  })
  default = null
}

variable "health_check_grace_period_seconds" {
  description = "Seconds ECS ignores ALB health after a task starts. Readiness gates the traffic anyway; this only stops ECS replacing a task still starting. Measured 2026-11-18: seat-inventory ready about 55 s after container start on 0.25 vCPU (Boot 46 s, JWKS warm-up 4 s), so 120 is about twice that (D7)."
  type        = number
  default     = 120
}

variable "requests_per_target" {
  description = "Target tracking: ALB requests per task per minute. A placeholder until the proof measures a task's capacity (D7, slice 5)."
  type        = number
  default     = 600
}

variable "scheduled_scaling" {
  description = "Scale ahead of a known on-sale (D7): name => an at(...) or cron(...) schedule in UTC with the min and max capacity from then on."
  type = map(object({
    schedule     = string
    min_capacity = number
    max_capacity = number
  }))
  default = {}

  validation {
    condition     = alltrue([for s in values(var.scheduled_scaling) : (can(regex("^(at|cron|rate)\\(", s.schedule)))])
    error_message = "A schedule is at(...), cron(...) or rate(...)."
  }
}
