# The CMK's key policy (D11). Each statement here is a grant whose absence fails silently: an
# alarm "fires" and no email arrives, or SNS drops a message into an encrypted queue while the
# publisher sees success. Plan-only with a mocked provider: no AWS, no account.

mock_provider "aws" {
  override_during = plan

  mock_data "aws_partition" {
    defaults = { partition = "aws", dns_suffix = "amazonaws.com" }
  }

  mock_data "aws_region" {
    defaults = { region = "eu-central-1" }
  }

  # The real data source renders JSON; the mock would return random text, which the key /
  # queue policy argument rejects. The tests assert on the statements, not on the JSON.
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  name                  = "twr-test"
  sqs_delivering_topics = ["twr-test-purchase-confirmed"]
}

run "key_is_rotated_and_recoverable" {
  command = plan

  assert {
    condition     = aws_kms_key.this.enable_key_rotation
    error_message = "Automatic key rotation must be on (D11)."
  }

  assert {
    condition     = aws_kms_key.this.deletion_window_in_days == 7
    error_message = "Deletion window is pinned to 7 days (D11)."
  }

  assert {
    condition     = aws_kms_alias.this.name == "alias/twr-test"
    error_message = "Alias is alias/<name>."
  }
}

run "iam_stays_in_charge_of_the_key" {
  command = plan

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.key.statement : (
      s.sid == "EnableIamPolicies"
      && contains(s.actions, "kms:*")
      && contains(one(s.principals).identifiers, "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root")
    )])
    error_message = "The account root statement is what prevents a KMS lockout (D11)."
  }
}

run "alarm_publishers_can_use_the_key" {
  command = plan

  # CloudWatch, Budgets and Cost Anomaly Detection publish to the encrypted ops-alarms topic
  # only if the key policy names them (D16).
  assert {
    condition = alltrue([for p in ["cloudwatch.amazonaws.com", "budgets.amazonaws.com", "costalerts.amazonaws.com"] :
      anytrue([for s in data.aws_iam_policy_document.key.statement : (
        s.sid == "AlarmPublishersUseKeyForOpsAlarms"
        && contains(one(s.principals).identifiers, p)
        && contains(s.actions, "kms:GenerateDataKey*")
        && contains(s.actions, "kms:Decrypt")
      )])
    ])
    error_message = "Each alarm publisher needs GenerateDataKey* and Decrypt on the key (D16)."
  }

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.key.statement : (
      s.sid == "AlarmPublishersUseKeyForOpsAlarms"
      && anytrue([for c in s.condition : (
        c.variable == "aws:SourceAccount" && contains(c.values, data.aws_caller_identity.current.account_id)
      )])
    )])
    error_message = "Service principals are confined to this account with aws:SourceAccount."
  }
}

run "topics_can_deliver_into_encrypted_queues" {
  command = plan

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.key.statement : (
      s.sid == "TopicsDeliverToEncryptedQueues"
      && contains(one(s.principals).identifiers, "sns.amazonaws.com")
      && contains(s.actions, "kms:GenerateDataKey*")
      && contains(s.actions, "kms:Decrypt")
      && anytrue([for c in s.condition : (
        c.test == "ArnEquals" && c.variable == "aws:SourceArn"
        && contains(c.values, "arn:aws:sns:eu-central-1:${data.aws_caller_identity.current.account_id}:twr-test-purchase-confirmed")
      )])
    )])
    error_message = "SNS needs the key, scoped by aws:SourceArn to the named topic, to deliver into a CMK queue (D11)."
  }
}

run "no_topic_grant_without_topics" {
  command = plan

  variables {
    sqs_delivering_topics = []
  }

  assert {
    condition     = !anytrue([for s in data.aws_iam_policy_document.key.statement : (s.sid == "TopicsDeliverToEncryptedQueues")])
    error_message = "No SNS grant when no topic delivers into a queue."
  }
}

run "log_delivery_can_read_an_encrypted_state_machine" {
  command = plan

  variables {
    state_machine_log_delivery = true
  }

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.key.statement : (
      s.sid == "LogDeliveryReadsEncryptedStateMachines"
      && contains(one(s.principals).identifiers, "delivery.logs.amazonaws.com")
      && s.actions == toset(["kms:Decrypt"])
      && anytrue([for c in s.condition : (
        c.variable == "aws:SourceAccount" && contains(c.values, data.aws_caller_identity.current.account_id)
      )])
    )])
    error_message = "CloudWatch log delivery decrypts the saga's execution data, Decrypt only, this account only (D11)."
  }
}

run "no_log_delivery_grant_by_default" {
  command = plan

  assert {
    condition     = !anytrue([for s in data.aws_iam_policy_document.key.statement : (s.sid == "LogDeliveryReadsEncryptedStateMachines")])
    error_message = "No log-delivery grant unless a state machine on the key logs."
  }
}

