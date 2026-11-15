variable "name_prefix" {
  description = "Repository name prefix, twr-<env> (D25). ci-images may push only to <prefix>-* (D14)."
  type        = string
}

variable "repositories" {
  description = "Deployables, named as their Gradle modules."
  type        = list(string)
}

variable "keep_images" {
  description = "Images kept per repository (D24)."
  type        = number
  default     = 3
}

variable "force_delete" {
  description = "Let destroy delete repositories that still hold images (rebuilt by CI anyway)."
  type        = bool
  default     = false
}
