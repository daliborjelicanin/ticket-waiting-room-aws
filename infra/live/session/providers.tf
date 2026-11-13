provider "aws" {
  region = var.region

  # Wrong-account guard (D12): a shell left on another profile fails at plan, before any change.
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      project    = "ticket-waiting-room"
      phase      = "2"
      env        = var.env
      stack      = "session"
      managed-by = "terraform"
    }
  }
}
