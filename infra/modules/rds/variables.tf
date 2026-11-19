variable "identifier" {
  description = "Instance identifier, twr-<env>-postgres; foundation's IAM matches the master secret by it (D25)."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,62}$", var.identifier))
    error_message = "Lowercase letters, digits and hyphens, starting with a letter, at most 63 characters."
  }
}

variable "engine_version" {
  description = "Postgres version, major.minor. The major is pinned (D9); the testcontainers image in the services' tests runs the same major."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+$", var.engine_version))
    error_message = "engine_version is major.minor, e.g. 18.6."
  }
}

variable "instance_class" {
  description = "Instance class. db.t4g.micro: Graviton, the session size (D9)."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  description = "gp3 storage in GB (20 is the minimum)."
  type        = number
  default     = 20
}

variable "kms_key_arn" {
  description = "The CMK (D11): storage and the managed master secret, and the service secrets."
  type        = string
}

variable "db_subnet_group_name" {
  description = "foundation's subnet group over the isolated db subnets (D3)."
  type        = string
}

variable "security_group_id" {
  description = "The db SG: in from checkout, issuance and migrate only (modules/network)."
  type        = string
}

variable "availability_zone" {
  description = "Single-AZ instance's zone, pinned next to the NAT and the cache (D3)."
  type        = string
  default     = null
}

variable "master_username" {
  description = "The master user: migration-only (D9, D10)."
  type        = string
  default     = "twr_admin"
}

variable "service_secret_names" {
  description = "Per-service login role => its Secrets Manager secret name. Names come from foundation, where the execution roles are scoped to them (D25)."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for role in keys(var.service_secret_names) : can(regex("^[a-z_][a-z0-9_]{0,62}$", role))])
    error_message = "Role names are lowercase Postgres identifiers."
  }
}

variable "service_secret_version" {
  description = "Version of the service passwords. Bump it to rotate them: bump, migrate, redeploy (D13, D23)."
  type        = number
  default     = 1
}

# --- production values, one variable each (D24) ---------------------------------------------

variable "multi_az" {
  description = "Standby in a second AZ. Dev false; production true (D9)."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  description = "Automated backups (and PITR). Dev 0: rebuilt each session from Flyway; production 7 (D24)."
  type        = number
  default     = 0
}

variable "deletion_protection" {
  description = "Dev false: destroyed every session; production true (D24)."
  type        = bool
  default     = false
}

variable "final_snapshot" {
  description = "Snapshot on destroy. Dev false; production true (D24)."
  type        = bool
  default     = false
}
