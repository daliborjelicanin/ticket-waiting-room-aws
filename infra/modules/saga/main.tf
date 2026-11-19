# The checkout saga's session half (D5, D13): the EventBridge connection its HTTP tasks call
# the services with. The state machine joins it here next (slice 3).
#
# Step Functions' HTTP task authenticates through a connection: EventBridge fetches a
# client_credentials token from Cognito with the ops client, caches it, and refreshes it before
# it expires. The saga presents it as a bearer token, and each service verifies it like any
# other JWT (security-starter). The saga's calls go through the public stage, so they pass the
# same WAF and the same JWT check as everyone else's (D5).
#
# The connection keeps its authorization in its own Secrets Manager secret
# (events!connection/<name>/…), on the AWS-owned key. D11 lists why it is not the CMK: the same
# client secret is in state (D13), so a key on this copy gates nothing.

resource "aws_cloudwatch_event_connection" "saga" {
  name               = "${var.name_prefix}-saga"
  description        = "Checkout saga -> services through the stage: Cognito client_credentials, ${var.scope} (D5, D13)."
  authorization_type = "OAUTH_CLIENT_CREDENTIALS"

  auth_parameters {
    oauth {
      authorization_endpoint = var.token_url
      http_method            = "POST"

      client_parameters {
        client_id     = var.client_id
        client_secret = var.client_secret
      }

      # Cognito reads the grant and the scope from the form body; the client authenticates
      # with HTTP Basic, which EventBridge builds from client_parameters.
      oauth_http_parameters {
        body {
          key   = "grant_type"
          value = "client_credentials"
        }
        body {
          key   = "scope"
          value = var.scope
        }
      }
    }
  }
}
