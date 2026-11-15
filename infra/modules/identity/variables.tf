variable "name_prefix" {
  description = "Name prefix, twr-<env> (D25)."
  type        = string
}

variable "deletion_protection" {
  description = "Cognito deletion protection (D24). End of phase: false, apply, destroy."
  type        = bool
  default     = true
}
