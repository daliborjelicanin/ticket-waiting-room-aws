# Queues, their DLQs and the topic fan-out (D11, retro §3). Every silent Phase 1 default is
# pinned here: redrive_allow_policy, derived visibility timeouts, raw_message_delivery, and the
# CMK with data-key reuse. The service-principal grant SNS needs on the CMK is in modules/kms.

locals {
  visibility_timeout = {
    for name, q in var.queues : name => (
      q.consumer == "lambda"
      ? 6 * q.function_timeout
      : q.batch_size * q.message_seconds * 3
    )
  }

  # One entry per topic -> queue subscription, keyed "<topic>/<queue>", as two flat string
  # maps (a map of objects from merge() is unresolvable in IntelliJ, infra/CLAUDE.md).
  subscription_topic = merge([
    for topic, t in var.topics : { for q in t.queue_subscribers : "${topic}/${q}" => topic }
  ]...)
  subscription_queue = merge([
    for topic, t in var.topics : { for q in t.queue_subscribers : "${topic}/${q}" => q }
  ]...)
}

resource "aws_sqs_queue" "dlq" {
  for_each = var.queues

  name                              = "${var.name_prefix}-${each.key}-dlq"
  message_retention_seconds         = var.dlq_retention_seconds
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300 # a load test must not become a KMS bill (D11)
}

# Only its own queue may name a DLQ as its dead-letter target. Nothing broke without it in
# Phase 1; anything could have redriven into it (retro §3).
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each = var.queues

  queue_url = aws_sqs_queue.dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.main[each.key].arn]
  })
}

resource "aws_sqs_queue" "main" {
  for_each = var.queues

  name                              = "${var.name_prefix}-${each.key}"
  visibility_timeout_seconds        = local.visibility_timeout[each.key]
  message_retention_seconds         = var.message_retention_seconds
  receive_wait_time_seconds         = 20 # long polling, matching the pump's poll-wait
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = each.value.max_receive_count
  })
}

resource "aws_sns_topic" "this" {
  for_each = var.topics

  name              = "${var.name_prefix}-${each.key}"
  kms_master_key_id = var.kms_key_arn
}

# A subscription's own DLQ: when SNS cannot deliver to the queue (the queue's policy or the
# key refuses it), it retries and then discards the message, with nothing failing anywhere a
# human would look. With a DLQ the message is kept and the DLQ alarm fires (D16).
resource "aws_sqs_queue" "subscription_dlq" {
  for_each = local.subscription_topic

  name                              = "${var.name_prefix}-${local.subscription_topic[each.key]}-to-${local.subscription_queue[each.key]}-dlq"
  message_retention_seconds         = var.dlq_retention_seconds
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = 300
}

# Per subscription, and only for queues: SNS's envelope turned every field null in Phase 1
# while the service reported success (retro §3).
resource "aws_sns_topic_subscription" "queue" {
  for_each = local.subscription_topic

  topic_arn            = aws_sns_topic.this[local.subscription_topic[each.key]].arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.main[local.subscription_queue[each.key]].arn
  raw_message_delivery = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.subscription_dlq[each.key].arn
  })
}

# Every queue that gets a policy, keyed: a main queue by its name, its DLQ "<name>-dlq", a
# subscription's DLQ "delivery/<topic>/<queue>". Flat maps (infra/CLAUDE.md).
locals {
  policy_queue_arn = merge(
    { for k, q in aws_sqs_queue.main : k => q.arn },
    { for k, q in aws_sqs_queue.dlq : "${k}-dlq" => q.arn },
    { for k, q in aws_sqs_queue.subscription_dlq : "delivery/${k}" => q.arn },
  )
  policy_queue_url = merge(
    { for k, q in aws_sqs_queue.main : k => q.id },
    { for k, q in aws_sqs_queue.dlq : "${k}-dlq" => q.id },
    { for k, q in aws_sqs_queue.subscription_dlq : "delivery/${k}" => q.id },
  )
}

# Every queue: TLS only. Subscribed queues, and each subscription's DLQ: the topic may deliver,
# with aws:SourceArn as the confused-deputy guard (without it any topic in any account could
# write here). Same-account producers and consumers get access through their IAM roles.
data "aws_iam_policy_document" "queue" {
  for_each = local.policy_queue_arn

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["sqs:*"]
    resources = [each.value]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  dynamic "statement" {
    for_each = (
      startswith(each.key, "delivery/")
      ? [split("/", each.key)[1]]
      : [for k, q in local.subscription_queue : local.subscription_topic[k] if q == each.key]
    )

    content {
      sid       = "AllowTopicDelivery${statement.key}"
      actions   = ["sqs:SendMessage"]
      resources = [each.value]

      principals {
        type        = "Service"
        identifiers = ["sns.amazonaws.com"]
      }

      condition {
        test     = "ArnEquals"
        variable = "aws:SourceArn"
        values   = [aws_sns_topic.this[statement.value].arn]
      }
    }
  }
}

resource "aws_sqs_queue_policy" "this" {
  for_each = data.aws_iam_policy_document.queue

  queue_url = local.policy_queue_url[each.key]
  policy    = each.value.json
}
