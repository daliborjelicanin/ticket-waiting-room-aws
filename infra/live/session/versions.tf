terraform {
  required_version = "~> 1.17"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
    # The per-service DB passwords, minted ephemeral (modules/rds, D13).
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # Partial configuration: the bucket name carries the account id, so it is never committed.
  # infra/scripts/tf-init.sh passes it at init time (-backend-config=bucket=...).
  backend "s3" {
    key          = "session.tfstate"
    region       = "eu-central-1"
    use_lockfile = true
    encrypt      = true
  }
}
