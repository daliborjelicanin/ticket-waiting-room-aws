variable "account_id" {
  description = "The only account this root may touch (provider allowed_account_ids). Supplied at run time by infra/scripts/tf-env.sh or a masked CI variable, never committed (CLAUDE.md, Secrets and Identifiers)."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "account_id must be a 12-digit AWS account id."
  }
}

variable "region" {
  description = "Home region."
  type        = string
  default     = "eu-central-1"
}

variable "env" {
  description = "Environment name, the middle part of every resource name (twr-<env>-..., D25)."
  type        = string
  default     = "dev"
}

variable "enable_alb" {
  description = "Internal ALB, VPC Link v2, the /{proxy+} private integration, deployment and stage (D5). Off = the REST API has no stage."
  type        = bool
  default     = false
}

variable "enable_waf" {
  description = "Web ACL on the REST stage, with logging (D6). Needs enable_alb: the ACL attaches to the stage."
  type        = bool
  default     = false

  validation {
    condition     = !var.enable_waf || var.enable_alb
    error_message = "enable_waf needs enable_alb: without the ALB there is no stage to attach the ACL to."
  }
}

variable "enable_nat" {
  description = "NAT gateway and the app tier's default routes (D4). Needed by anything that starts an ECS task; not by the edge alone."
  type        = bool
  default     = false
}

variable "nat_mode" {
  description = "zonal (sessions: one gateway in AZ a) or regional (the production shape, D4)."
  type        = string
  default     = "zonal"

  validation {
    condition     = contains(["zonal", "regional"], var.nat_mode)
    error_message = "nat_mode is zonal or regional."
  }
}

variable "waf_baseline_mode" {
  description = "AWS managed baseline on the whole stage: count until its sampled requests (the saga's included, slice 3) are reviewed, then block (D5, D6)."
  type        = string
  default     = "count"

  validation {
    condition     = contains(["count", "block"], var.waf_baseline_mode)
    error_message = "waf_baseline_mode is count or block."
  }
}

variable "loadtest_allow_cidr" {
  description = "The load test runner's address (a.b.c.d/32), allowed ahead of the buyer rules for the concurrency proof only. Pass with -var at apply time; never committed, never in a tfvars file (D6, CLAUDE.md Secrets)."
  type        = string
  default     = null
  sensitive   = true
}

variable "services" {
  description = "ECS services to run (D7, D23). A service joins this list once its wiring is in main.tf; the others follow in slice 3. Needs enable_nat (ECR, logs, Cognito's JWKS) and, for an HTTP service, enable_alb."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for s in var.services : contains(["seat-inventory"], s)])
    error_message = "Only seat-inventory is wired so far."
  }

  validation {
    condition     = length(var.services) == 0 || (var.enable_nat && var.enable_alb)
    error_message = "Services need enable_nat (nothing in a task starts without egress, D4) and enable_alb (their routes)."
  }
}

variable "image_digests" {
  description = "Deployed image digest per Gradle module (sha256:...), resolved from SSM /twr/<env>/images at dispatch by tf-env.sh or the workflow (D15). Never read from SSM by Terraform, so the plan shows what runs."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for d in values(var.image_digests) : can(regex("^sha256:[0-9a-f]{64}$", d))])
    error_message = "Each digest is sha256:<64 hex characters>."
  }
}

variable "capacity" {
  description = "FARGATE_SPOT (dev sessions) or FARGATE (the D7 mix: one on-demand task, Spot above it; the proof run)."
  type        = string
  default     = "FARGATE_SPOT"

  validation {
    condition     = contains(["FARGATE_SPOT", "FARGATE"], var.capacity)
    error_message = "capacity is FARGATE_SPOT or FARGATE."
  }
}

variable "desired_count" {
  description = "Tasks per service at start, and the autoscaling minimum."
  type        = number
  default     = 1
}

variable "task_size" {
  description = "Per service: feature (0.25 vCPU) or proof (0.5 vCPU), 1 GB either way (D7). Unlisted services run feature."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for v in values(var.task_size) : contains(["feature", "proof"], v)])
    error_message = "task_size values are feature or proof."
  }
}
