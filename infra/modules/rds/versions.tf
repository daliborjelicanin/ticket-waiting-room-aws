terraform {
  required_version = "~> 1.17"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
    # ephemeral "random_password" (D13): needs 3.7 or later.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }
}
