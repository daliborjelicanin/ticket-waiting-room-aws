variable "name" {
  description = "Name tag of the gateway and its address, twr-<env>-nat (D25)."
  type        = string
}

variable "mode" {
  description = "zonal: one gateway in AZ a, for sessions. regional: the production shape, one switch away (D4)."
  type        = string
  default     = "zonal"

  validation {
    condition     = contains(["zonal", "regional"], var.mode)
    error_message = "mode is zonal or regional."
  }
}

variable "vpc_id" {
  description = "The VPC, for regional mode."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets in AZ order; zonal mode uses the first (AZ a)."
  type        = list(string)

  validation {
    condition     = length(var.public_subnet_ids) > 0
    error_message = "Zonal mode needs a public subnet."
  }
}

variable "app_route_table_ids" {
  description = "The app tier's route tables; each gets 0.0.0.0/0 to the gateway. The data tier gets none (D3)."
  type        = list(string)

  validation {
    condition     = length(var.app_route_table_ids) > 0
    error_message = "At least one app route table."
  }
}
