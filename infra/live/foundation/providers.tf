locals {
  # One set for both providers, so the us-east-1 alerts can never be tagged differently.
  default_tags = {
    project    = "ticket-waiting-room"
    phase      = "2"
    env        = var.env
    stack      = "foundation"
    managed-by = "terraform"
  }
}

provider "aws" {
  region = var.region

  # Wrong-account guard (D12): a shell left on another profile fails at plan, before any change.
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = local.default_tags
  }
}

# Global-service events (IAM, root sign-in) are recorded in us-east-1 (D24).
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  allowed_account_ids = [var.account_id]

  default_tags {
    tags = local.default_tags
  }
}
