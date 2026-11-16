# Queues and fan-out (retro §3, D11): derived visibility timeouts, redrive both ways, raw
# delivery, the CMK with data-key reuse, and a queue policy that admits exactly its own topic.
#
# The redrive and SourceArn checks compare one resource's ARN with another's, so each queue
# gets a distinct, well-formed fake ARN ("aws" in the account field: a 12-digit id may not be
# committed, CLAUDE.md). The mock's own random strings are unknown on plan and are rejected by
# SNS's ARN validation.

override_resource {
  target          = aws_sqs_queue.main["qr-generation"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:eu-central-1:aws:twr-test-qr-generation" }
}

override_resource {
  target          = aws_sqs_queue.main["notifications"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:eu-central-1:aws:twr-test-notifications" }
}

override_resource {
  target          = aws_sqs_queue.dlq["qr-generation"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:eu-central-1:aws:twr-test-qr-generation-dlq" }
}

override_resource {
  target          = aws_sqs_queue.dlq["notifications"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:eu-central-1:aws:twr-test-notifications-dlq" }
}

override_resource {
  target          = aws_sns_topic.this["purchase-confirmed"]
  override_during = plan
  values          = { arn = "arn:aws:sns:eu-central-1:aws:twr-test-purchase-confirmed" }
}

override_resource {
  target          = aws_sqs_queue.subscription_dlq["purchase-confirmed/notifications"]
  override_during = plan
  values          = { arn = "arn:aws:sqs:eu-central-1:aws:twr-test-purchase-confirmed-to-notifications-dlq" }
}

mock_provider "aws" {
  # The real data source renders JSON; the mock would return random text, which the key /
  # queue policy argument rejects. The tests assert on the statements, not on the JSON.
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
}

variables {
  name_prefix = "twr-test"
  kms_key_arn = "arn:aws:kms:eu-central-1:<account-id>:key/test"

  queues = {
    qr-generation = {
      consumer          = "lambda"
      function_timeout  = 60
      max_receive_count = 5
    }
    notifications = {
      consumer          = "pump"
      batch_size        = 10
      message_seconds   = 4
      max_receive_count = 3
    }
  }

  topics = {
    purchase-confirmed = { queue_subscribers = ["notifications"] }
  }
}

run "visibility_timeouts_are_derived" {
  command = plan

  assert {
    condition     = aws_sqs_queue.main["qr-generation"].visibility_timeout_seconds == 360
    error_message = "Lambda consumer: 6 x the function timeout (60 -> 360, retro §3)."
  }

  assert {
    condition     = aws_sqs_queue.main["notifications"].visibility_timeout_seconds == 120
    error_message = "Pump consumer: batch x seconds per message x 3 (10 x 4 x 3 = 120, retro §3)."
  }
}

run "every_queue_is_encrypted_with_reuse" {
  command = plan

  assert {
    condition = alltrue([for q in concat(values(aws_sqs_queue.main), values(aws_sqs_queue.dlq)) : (
      q.kms_master_key_id == var.kms_key_arn && q.kms_data_key_reuse_period_seconds == 300
    )])
    error_message = "Main queues and DLQs use the CMK with 300 s data-key reuse (D11)."
  }

  assert {
    condition     = aws_sns_topic.this["purchase-confirmed"].kms_master_key_id == var.kms_key_arn
    error_message = "The topic uses the CMK: it carries the buyer's email (D11)."
  }
}

run "redrive_both_ways" {
  command = plan

  assert {
    condition = (
      jsondecode(aws_sqs_queue.main["qr-generation"].redrive_policy).deadLetterTargetArn == aws_sqs_queue.dlq["qr-generation"].arn
      && jsondecode(aws_sqs_queue.main["qr-generation"].redrive_policy).maxReceiveCount == 5
    )
    error_message = "Each queue redrives to its own DLQ after max_receive_count."
  }

  assert {
    condition = (
      jsondecode(aws_sqs_queue_redrive_allow_policy.dlq["notifications"].redrive_allow_policy).redrivePermission == "byQueue"
      && jsonencode(jsondecode(aws_sqs_queue_redrive_allow_policy.dlq["notifications"].redrive_allow_policy).sourceQueueArns) == jsonencode([aws_sqs_queue.main["notifications"].arn])
    )
    error_message = "Only its own queue may name a DLQ (redrive_allow byQueue, retro §3)."
  }
}

run "subscription_is_raw_and_targets_its_queue" {
  command = plan

  assert {
    condition = (
      aws_sns_topic_subscription.queue["purchase-confirmed/notifications"].raw_message_delivery
      && aws_sns_topic_subscription.queue["purchase-confirmed/notifications"].protocol == "sqs"
      && aws_sns_topic_subscription.queue["purchase-confirmed/notifications"].endpoint == aws_sqs_queue.main["notifications"].arn
    )
    error_message = "Raw delivery: SNS's envelope made every field null in Phase 1 (retro §3)."
  }

  assert {
    condition     = length(aws_sns_topic_subscription.queue) == 1
    error_message = "One subscription per (topic, subscribed queue)."
  }

  assert {
    condition     = jsondecode(aws_sns_topic_subscription.queue["purchase-confirmed/notifications"].redrive_policy).deadLetterTargetArn == aws_sqs_queue.subscription_dlq["purchase-confirmed/notifications"].arn
    error_message = "A subscription keeps what SNS cannot deliver in its own DLQ, instead of discarding it (D16)."
  }

  assert {
    condition = (
      aws_sqs_queue.subscription_dlq["purchase-confirmed/notifications"].kms_master_key_id == var.kms_key_arn
      && aws_sqs_queue.subscription_dlq["purchase-confirmed/notifications"].message_retention_seconds == 1209600
    )
    error_message = "The subscription DLQ is encrypted like the others and keeps 14 days."
  }

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.queue["delivery/purchase-confirmed/notifications"].statement : (
      contains(s.actions, "sqs:SendMessage")
      && anytrue([for c in s.condition : (c.variable == "aws:SourceArn" && contains(c.values, aws_sns_topic.this["purchase-confirmed"].arn))])
    )])
    error_message = "SNS may write into the subscription's DLQ, only for its topic."
  }

  assert {
    condition     = output.dlq_names["delivery/purchase-confirmed/notifications"] == aws_sqs_queue.subscription_dlq["purchase-confirmed/notifications"].name
    error_message = "The subscription DLQ is in dlq_names, so it gets an alarm."
  }
}

run "queue_policies_admit_only_their_topic" {
  command = plan

  assert {
    condition = alltrue([for k, d in data.aws_iam_policy_document.queue : anytrue([for s in d.statement : (
      s.sid == "DenyInsecureTransport" && s.effect == "Deny"
    )])])
    error_message = "Every queue, DLQs included, denies non-TLS access."
  }

  assert {
    condition = anytrue([for s in data.aws_iam_policy_document.queue["notifications"].statement : (
      contains(s.actions, "sqs:SendMessage")
      && contains(one(s.principals).identifiers, "sns.amazonaws.com")
      && anytrue([for c in s.condition : (
        c.variable == "aws:SourceArn" && length(c.values) == 1 && contains(c.values, aws_sns_topic.this["purchase-confirmed"].arn)
      )])
    )])
    error_message = "The subscribed queue admits SNS, confined to its topic by aws:SourceArn."
  }

  assert {
    condition     = length(data.aws_iam_policy_document.queue["qr-generation"].statement) == 1
    error_message = "A queue no topic delivers to admits no service principal."
  }
}

run "unknown_consumer_is_refused" {
  command = plan

  variables {
    queues = {
      q = { consumer = "ecs", function_timeout = 30, max_receive_count = 3 }
    }
    topics = {}
  }

  expect_failures = [var.queues]
}

run "lambda_consumer_without_timeout_is_refused" {
  command = plan

  variables {
    queues = {
      q = { consumer = "lambda", max_receive_count = 3 }
    }
    topics = {}
  }

  expect_failures = [var.queues]
}
