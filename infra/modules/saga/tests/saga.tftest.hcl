# The checkout saga (D5, D11, D12, D13, D16): the connection, Cognito client_credentials with
# the ops client and the ops scope only, under the name the saga role may use; and the state
# machine, built from the real docs/checkout-saga.asl.json with its three placeholders
# replaced, on the CMK, logging without execution data, traced. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_cloudwatch_event_connection.saga
    values = { arn = "arn:aws:events:eu-central-1:aws:connection/twr-test-saga/00000000-0000-0000-0000-000000000000" }
  }
}

variables {
  name_prefix   = "twr-test"
  token_url     = "https://twr-test.auth.eu-central-1.amazoncognito.com/oauth2/token"
  client_id     = "testclientid"
  client_secret = "not-a-real-secret"

  definition                   = file("../../../docs/checkout-saga.asl.json")
  role_arn                     = "arn:aws:iam::aws:role/twr-test-saga"
  issuance_queue_url           = "https://sqs.eu-central-1.amazonaws.com/aws/twr-test-ticket-issuance"
  purchase_confirmed_topic_arn = "arn:aws:sns:eu-central-1:aws:twr-test-purchase-confirmed"
  kms_key_arn                  = "arn:aws:kms:eu-central-1:aws:key/00000000-0000-0000-0000-000000000000"
  log_group_arn                = "arn:aws:logs:eu-central-1:aws:log-group:/aws/vendedlogs/states/twr-test-checkout-saga"
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

run "the_state_machine_foundation_starts" {
  command = plan

  assert {
    condition     = aws_sfn_state_machine.saga.name == "twr-test-checkout-saga" && aws_sfn_state_machine.saga.type == "STANDARD"
    error_message = "<prefix>-checkout-saga, the name foundation's purchase integration and IAM grants use (D25); STANDARD."
  }

  assert {
    condition     = aws_sfn_state_machine.saga.role_arn == var.role_arn
    error_message = "Runs as foundation's saga role."
  }
}

run "placeholders_replaced_in_the_real_definition" {
  command = plan

  assert {
    condition = (
      !strcontains(aws_sfn_state_machine.saga.definition, "__")
      && strcontains(aws_sfn_state_machine.saga.definition, "connection/twr-test-saga/")
      && strcontains(aws_sfn_state_machine.saga.definition, var.issuance_queue_url)
      && strcontains(aws_sfn_state_machine.saga.definition, var.purchase_confirmed_topic_arn)
    )
    error_message = "All three placeholders replaced: the connection, the issuance queue, the topic."
  }

  assert {
    condition     = jsondecode(aws_sfn_state_machine.saga.definition).StartAt == "CheckAdmission"
    error_message = "Still the saga, still JSON, still starting with the admission check."
  }
}

run "execution_data_on_the_cmk_and_out_of_the_logs" {
  command = plan

  assert {
    condition = (
      aws_sfn_state_machine.saga.encryption_configuration[0].type == "CUSTOMER_MANAGED_KMS_KEY"
      && aws_sfn_state_machine.saga.encryption_configuration[0].kms_key_id == var.kms_key_arn
    )
    error_message = "Execution input and history carry the buyer's email: the CMK (D11)."
  }

  assert {
    condition = (
      !aws_sfn_state_machine.saga.logging_configuration[0].include_execution_data
      && aws_sfn_state_machine.saga.logging_configuration[0].log_destination == "${var.log_group_arn}:*"
    )
    error_message = "Every transition logged to the saga's group, never its data (D11: no personal data in logs)."
  }

  assert {
    condition     = aws_sfn_state_machine.saga.tracing_configuration[0].enabled
    error_message = "X-Ray on: the saga is the trace from the edge to the services (D16)."
  }
}

run "a_definition_without_the_placeholders_is_refused" {
  command = plan

  variables {
    definition = "{\"StartAt\":\"X\",\"States\":{\"X\":{\"Type\":\"Succeed\"}}}"
  }

  expect_failures = [var.definition]
}

