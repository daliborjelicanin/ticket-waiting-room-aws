variable "name" {
  description = "Task definition family, e.g. twr-dev-migrate-checkout. infra/scripts/migrate.sh runs the family's latest revision."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,62}$", var.name))
    error_message = "Lowercase letters, digits and hyphens, 3 to 63 characters, starting with a letter."
  }
}

variable "image_uri" {
  description = "The service's own image, by digest (D15): the migration is the image's migrate profile, not a separate tool (D10)."
  type        = string

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image_uri))
    error_message = "Pin the image by digest: the migration must run exactly the schema the deploy will ship (D15)."
  }
}

variable "cpu" {
  description = "Task CPU units. Half a vCPU: the whole Spring context starts (Hibernate validates the entities after Flyway), and at 0.25 vCPU that alone is about a minute (D7)."
  type        = number
  default     = 512

  validation {
    condition     = contains([256, 512, 1024], var.cpu)
    error_message = "One of 256, 512 or 1024 CPU units."
  }
}

variable "memory" {
  description = "Task memory in MiB. 1 GB, as the services run with: the same context, no collector beside it."
  type        = number
  default     = 1024
}

variable "task_role_arn" {
  description = "The migrate task role (modules/iam): nothing in AWS, Flyway runs over a password."
  type        = string
}

variable "execution_role_arn" {
  description = "The migrate execution role (modules/iam): pulls the service image, writes the log stream, resolves the master and the service secret."
  type        = string
}

variable "log_group_name" {
  description = "Foundation's /twr/<env>/ecs/migrate log group (D25)."
  type        = string
}

variable "region" {
  description = "Region of the log group."
  type        = string
}

variable "db_address" {
  description = "The RDS instance's DNS name (modules/rds address)."
  type        = string
}

variable "db_port" {
  description = "The RDS instance's port."
  type        = number
  default     = 5432
}

variable "database" {
  description = "The service's database, created by the migration if missing: checkout or ticketing (D9)."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{0,62}$", var.database))
    error_message = "A plain lowercase Postgres identifier."
  }
}

variable "master_secret_arn" {
  description = "The RDS-managed master secret (modules/rds master_secret_arn). The migration is the only thing that runs as master (D9, D10)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:secretsmanager:", var.master_secret_arn))
    error_message = "A full Secrets Manager ARN: ECS reads a JSON key only from a full ARN."
  }
}

variable "app_secret_arn" {
  description = "The service role's secret (modules/rds service_secret_arns): the role to create and the password to set on it (D13)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:secretsmanager:", var.app_secret_arn))
    error_message = "A full Secrets Manager ARN: ECS reads a JSON key only from a full ARN."
  }
}
