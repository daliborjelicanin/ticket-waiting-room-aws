variable "name" {
  description = "Replication group id and subnet group name, twr-<env>-queue (D25)."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,39}$", var.name))
    error_message = "Lowercase letters, digits and hyphens, starting with a letter, at most 40 characters (ElastiCache)."
  }
}

variable "engine_version" {
  description = "Valkey version, major.minor. The parameter group family follows the major."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+$", var.engine_version))
    error_message = "engine_version is major.minor, e.g. 9.1."
  }
}

variable "node_type" {
  description = "Node type. cache.t4g.micro: Graviton, the session size (D8)."
  type        = string
  default     = "cache.t4g.micro"
}

variable "subnet_ids" {
  description = "The isolated db subnets (D3)."
  type        = list(string)
}

variable "security_group_id" {
  description = "The cache SG: in from waiting-room only (modules/network)."
  type        = string
}

variable "availability_zone" {
  description = "The one node's zone, pinned next to the NAT (D3)."
  type        = string
}

variable "kms_key_arn" {
  description = "The CMK, for encryption at rest (D11)."
  type        = string
}
