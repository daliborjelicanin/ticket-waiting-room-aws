# The Cognito user pool and its OAuth pieces (runbook-cognito-auth.md; D13, D19, D24).
#
# Two doors, told apart by the access token's scope, never by the network path:
#   web    buyers, public client, USER_PASSWORD_AUTH, no custom scope
#   ops    saga + operators, confidential, client_credentials, ticketing/ops
#   gate   gate scanners, confidential, client_credentials, ticketing/gate (D19)
# The services verify every token themselves (security-starter); nothing here authorises.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

resource "aws_cognito_user_pool" "this" {
  name = "${var.name_prefix}-users"

  # Users and their passwords are the one store code cannot rebuild (D24). End of phase:
  # set the variable to false, apply, destroy.
  deletion_protection = var.deletion_protection ? "ACTIVE" : "INACTIVE"

  # Essentials: the free tier covers this pool; Plus (threat protection) bills per MAU.
  user_pool_tier = "ESSENTIALS"

  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  # No self sign-up: there is no frontend, and an open public client lets anyone mint users
  # and spend the pool's email quota. Buyers are created by create-test-users.sh.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  # Length over composition (NIST SP 800-63B): Phase 1 had 12 with no classes, tightened to 14.
  password_policy {
    minimum_length                   = 14
    require_lowercase                = false
    require_uppercase                = false
    require_numbers                  = false
    require_symbols                  = false
    temporary_password_validity_days = 1
  }

  mfa_configuration = "OFF" # test users only; production buyers would get optional TOTP

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
}

# The domain exists for the token endpoint client_credentials needs, not for a login page.
# Prefixes are global, hence the account id (read at plan time, D25).
resource "aws_cognito_user_pool_domain" "this" {
  domain       = "${var.name_prefix}-${data.aws_caller_identity.current.account_id}"
  user_pool_id = aws_cognito_user_pool.this.id
}

# "ticketing/ops" and "ticketing/gate" are strings in TicketingScopes, checked by the services
# and read by no compiler: the same class of contract as SeatTableSchema.
resource "aws_cognito_resource_server" "ticketing" {
  user_pool_id = aws_cognito_user_pool.this.id
  identifier   = "ticketing"
  name         = "Ticketing services"

  scope {
    scope_name        = "ops"
    scope_description = "Operator and saga access"
  }

  scope {
    scope_name        = "gate"
    scope_description = "Gate scanners: ticket verification and check-in (D19)"
  }
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${var.name_prefix}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret     = false # public: a browser cannot keep a secret
  explicit_auth_flows = ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"]

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 1
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  prevent_user_existence_errors = "ENABLED" # sign-in must not reveal which emails exist
  enable_token_revocation       = true
}

resource "aws_cognito_user_pool_client" "m2m" {
  for_each = {
    ops  = "ticketing/ops"
    gate = "ticketing/gate"
  }

  name         = "${var.name_prefix}-${each.key}"
  user_pool_id = aws_cognito_user_pool.this.id

  # The secret lands in state: the one accepted exception (D13), why the state bucket is locked.
  generate_secret                      = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_scopes                 = [each.value]
  supported_identity_providers         = ["COGNITO"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH"] # API minimum; no user flows

  access_token_validity = 60
  token_validity_units {
    access_token = "minutes"
  }

  enable_token_revocation = true

  depends_on = [aws_cognito_resource_server.ticketing] # the scope must exist first
}
