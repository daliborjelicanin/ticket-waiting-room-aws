# The checkout saga's session half (D5, D11, D13): the state machine, and the EventBridge
# connection its HTTP tasks call the services with.
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

# --- the state machine ----------------------------------------------------------------------
# One definition, docs/checkout-saga.asl.json, read by Terraform here and by the two contract
# tests (checkout, ticket-issuance) that pin its wire format: three placeholders are replaced
# rather than the file copied into a template, so the deployed saga and the tested one cannot
# drift apart.

locals {
  definition = replace(replace(replace(var.definition,
    "__CONNECTION_ARN__", aws_cloudwatch_event_connection.saga.arn),
    "__QUEUE_URL__", var.issuance_queue_url),
  "__TOPIC_ARN__", var.purchase_confirmed_topic_arn)
}

resource "aws_sfn_state_machine" "saga" {
  # The name foundation's purchase integration starts and its IAM grants are scoped to (D25).
  name     = "${var.name_prefix}-checkout-saga"
  type     = "STANDARD" # exactly-once steps, a year of history, compensation that can wait
  role_arn = var.role_arn

  definition = local.definition

  # Execution input and history carry the buyer's email: customer data, so the CMK (D11).
  encryption_configuration {
    type                              = "CUSTOMER_MANAGED_KMS_KEY"
    kms_key_id                        = var.kms_key_arn
    kms_data_key_reuse_period_seconds = 300 # as the queues (D11): a load test is not a KMS bill
  }

  # Every state transition, without its data: the log says which step failed and why, never
  # whose purchase it was (D11: no personal data in logs).
  logging_configuration {
    log_destination        = "${var.log_group_arn}:*"
    include_execution_data = false
    level                  = "ALL"
  }

  # The saga is the trace's spine: API Gateway -> Step Functions -> the services (D16).
  tracing_configuration {
    enabled = true
  }

  lifecycle {
    precondition {
      condition     = !strcontains(local.definition, "__CONNECTION_ARN__") && !strcontains(local.definition, "__QUEUE_URL__") && !strcontains(local.definition, "__TOPIC_ARN__")
      error_message = "A placeholder survived the substitution."
    }
  }
}

