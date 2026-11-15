# Lambda roles: AWSLambdaBasicExecutionRole (logs:* on *) replaced by the function's own log
# group, which the lambda-function module declares with a retention (retro §3, §4). Lambda
# documents no source conditions for assuming an execution role, so the trust has none.

locals {
  function_log_group = { for f, n in var.log_groups.lambda : f => "${local.log_group_arn["lambda/${f}"]}:*" }

  lambda_policies = {
    hold-cleanup   = data.aws_iam_policy_document.hold_cleanup.json
    qr-generation  = data.aws_iam_policy_document.qr_generation.json
    queue-notifier = data.aws_iam_policy_document.queue_notifier.json
  }
}

data "aws_iam_policy_document" "lambda_logs" {
  for_each = var.log_groups.lambda

  source_policy_documents = [data.aws_iam_policy_document.xray.json]

  statement {
    sid       = "WriteOwnLogGroup"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [local.function_log_group[each.key]]
  }
}

# Reclaims expired holds. Scan (one partition per event, no status index: runbook-hold-cleanup)
# and a conditional UpdateItem. No DeleteItem: a reclaimed seat goes back to AVAILABLE.
data "aws_iam_policy_document" "hold_cleanup" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["hold-cleanup"].json]

  statement {
    sid       = "ReclaimExpiredHolds"
    actions   = ["dynamodb:Scan", "dynamodb:UpdateItem"]
    resources = [var.seat_table_arn]
  }
}

data "aws_iam_policy_document" "qr_generation" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["qr-generation"].json]

  # The event source mapping polls with the function's role.
  statement {
    sid       = "ConsumeQrJobs"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [var.queue_arns["qr-generation"]]
  }

  statement {
    sid       = "ReadTheQueueOnTheKey"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["sqs"]]
    }
  }

  # PutObject only: the key is derived from the ticket id, so a redelivery rewrites the same
  # bytes (idempotent by construction); no read, no delete.
  statement {
    sid       = "WriteQrImages"
    actions   = ["s3:PutObject"]
    resources = ["${var.qr_bucket_arn}/tickets/*"]
  }

  statement {
    sid       = "EncryptQrImages"
    actions   = ["kms:GenerateDataKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["s3"]]
    }
  }
}

data "aws_iam_policy_document" "queue_notifier" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["queue-notifier"].json]

  statement {
    sid       = "ConsumeAdmissions"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [var.queue_arns["queue-admissions"]]
  }

  statement {
    sid       = "ReadTheQueueOnTheKey"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["sqs"]]
    }
  }

  # The GSI needs its own ARN beside the table's, or Query on it is denied (retro §3).
  # DeleteItem prunes a socket API Gateway answers 410 for.
  statement {
    sid       = "FindAndPruneSockets"
    actions   = ["dynamodb:Query", "dynamodb:DeleteItem"]
    resources = [var.connections_table_arn, "${var.connections_table_arn}/index/by-token"]
  }

  # Push frames back through the WebSocket API's management endpoint. The API is built in a
  # later piece; until its ARN is passed in, the statement is absent.
  dynamic "statement" {
    for_each = var.websocket_api_execution_arn == null ? [] : [1]

    content {
      sid       = "PushFrames"
      actions   = ["execute-api:ManageConnections"]
      resources = ["${var.websocket_api_execution_arn}/*/POST/@connections/*"]
    }
  }
}
