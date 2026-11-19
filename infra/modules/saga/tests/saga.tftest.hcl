# The saga's connection (D5, D12, D13): Cognito client_credentials with the ops client, the ops
# scope only, under the name the saga role is allowed to use. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan
}

variables {
  name_prefix   = "twr-test"
  token_url     = "https://twr-test.auth.eu-central-1.amazoncognito.com/oauth2/token"
  client_id     = "testclientid"
  client_secret = "not-a-real-secret"
}

run "client_credentials_against_cognito" {
  command = plan

  assert {
    condition = (
      aws_cloudwatch_event_connection.saga.authorization_type == "OAUTH_CLIENT_CREDENTIALS"
      && aws_cloudwatch_event_connection.saga.auth_parameters[0].oauth[0].authorization_endpoint == var.token_url
      && aws_cloudwatch_event_connection.saga.auth_parameters[0].oauth[0].http_method == "POST"
    )
    error_message = "OAuth client_credentials, POSTed to the Cognito token endpoint."
  }

  assert {
    condition     = aws_cloudwatch_event_connection.saga.auth_parameters[0].oauth[0].client_parameters[0].client_id == var.client_id
    error_message = "The ops client authenticates the saga."
  }

  # body is a set of blocks: compared as a key => value map.
  assert {
    condition = jsonencode({ for b in aws_cloudwatch_event_connection.saga.auth_parameters[0].oauth[0].oauth_http_parameters[0].body : b.key => b.value }) == jsonencode({
      grant_type = "client_credentials"
      scope      = "ticketing/ops"
    })
    error_message = "Exactly the client_credentials grant and the ticketing/ops scope, nothing wider (D12)."
  }
}

run "named_where_the_saga_role_may_use_it" {
  command = plan

  assert {
    condition     = aws_cloudwatch_event_connection.saga.name == "twr-test-saga"
    error_message = "<prefix>-saga: the saga role's RetrieveConnectionCredentials grant covers connection/<prefix>-*/* only."
  }
}

run "a_wider_scope_is_refused" {
  command = plan

  variables {
    scope = "ticketing/gate"
  }

  expect_failures = [var.scope]
}

run "a_token_url_that_is_not_cognitos_is_refused" {
  command = plan

  variables {
    token_url = "http://example.test/token"
  }

  expect_failures = [var.token_url]
}
