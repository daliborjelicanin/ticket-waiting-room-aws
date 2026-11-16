# The user pool (D12, D24): deletion protection on by default, no self sign-up, length-based
# passwords, and clients that can do only what each one is for. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  mock_data "aws_region" {
    defaults = { region = "eu-central-1" }
  }
}

variables {
  name_prefix = "twr-test"
}

run "pool_is_protected_and_closed" {
  command = plan

  assert {
    condition     = aws_cognito_user_pool.this.deletion_protection == "ACTIVE"
    error_message = "Deletion protection on by default: users are the one store code cannot rebuild (D24)."
  }

  assert {
    condition     = aws_cognito_user_pool.this.admin_create_user_config[0].allow_admin_create_user_only
    error_message = "No self sign-up: an open public client lets anyone mint users."
  }

  assert {
    condition     = aws_cognito_user_pool.this.password_policy[0].minimum_length >= 14
    error_message = "Passwords of at least 14 characters (NIST SP 800-63B: length over composition)."
  }
}

run "protection_follows_the_variable" {
  command = plan

  variables {
    deletion_protection = false
  }

  assert {
    condition     = aws_cognito_user_pool.this.deletion_protection == "INACTIVE"
    error_message = "End of phase: false, apply, destroy (D24)."
  }
}

run "clients_do_only_their_job" {
  command = plan

  assert {
    condition = (
      !aws_cognito_user_pool_client.web.generate_secret
      && aws_cognito_user_pool_client.web.prevent_user_existence_errors == "ENABLED"
    )
    error_message = "The web client is public (no secret) and does not reveal which emails exist."
  }

  assert {
    condition = alltrue([for k, c in aws_cognito_user_pool_client.m2m : (
      c.generate_secret
      && c.allowed_oauth_flows == toset(["client_credentials"])
      && c.allowed_oauth_scopes == toset(["ticketing/${k}"])
    )])
    error_message = "Each M2M client: confidential, client_credentials only, its own scope only (D19)."
  }
}
