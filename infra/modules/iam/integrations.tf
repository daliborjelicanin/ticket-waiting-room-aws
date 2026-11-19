# Roles that are another service's identity when it calls into ours (see main.tf).

locals {
  saga_arn_pattern = "${local.arn}:states:${local.region}:${local.account_id}:stateMachine:${var.saga_state_machine_name}"

  integration_roles = {
    # API Gateway's $connect / $disconnect service integrations onto DynamoDB (no Lambda).
    websocket-apigw = {
      principal  = "apigateway.amazonaws.com"
      source_arn = null
      policy     = data.aws_iam_policy_document.websocket_apigw.json
    }
    # API Gateway's POST /events/{id}/purchase -> states:StartExecution integration.
    purchase-apigw = {
      principal  = "apigateway.amazonaws.com"
      source_arn = null
      policy     = data.aws_iam_policy_document.purchase_apigw.json
    }
    # API Gateway's account-wide CloudWatch role: WebSocket and REST stages can write access
    # logs only through it. One per account and region (aws_api_gateway_account).
    apigateway-cloudwatch = {
      principal  = "apigateway.amazonaws.com"
      source_arn = null
      policy     = data.aws_iam_policy_document.apigateway_cloudwatch.json
    }
    # EventBridge Scheduler invoking hold-cleanup. Phase 1's was auto-named by the console.
    hold-cleanup-scheduler = {
      principal  = "scheduler.amazonaws.com"
      source_arn = "${local.arn}:scheduler:${local.region}:${local.account_id}:schedule/default/${var.name_prefix}-*"
      policy     = data.aws_iam_policy_document.scheduler.json
    }
    # The checkout saga (Step Functions).
    saga = {
      principal  = "states.amazonaws.com"
      source_arn = local.saga_arn_pattern
      policy     = data.aws_iam_policy_document.saga.json
    }
  }
}

data "aws_iam_policy_document" "websocket_apigw" {
  # One unconditional write per connect/disconnect against a key API Gateway already holds.
  statement {
    sid       = "MaintainConnectionRegistry"
    actions   = ["dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = [var.connections_table_arn]
  }
}

# The action set of AmazonAPIGatewayPushToCloudWatchLogs, which UpdateAccount checks for
# itself: a policy scoped to the two access log groups was refused ("The role ARN does not
# have required permissions configured", 2026-11-16, 3 min of retries, so not propagation).
# The managed policy grants these on "*"; here they stop at this account and region.
data "aws_iam_policy_document" "apigateway_cloudwatch" {
  statement {
    sid = "PushToCloudWatchLogsInThisAccountAndRegion"
    actions = [
      "logs:CreateLogGroup", "logs:CreateLogStream", "logs:DescribeLogGroups",
      "logs:DescribeLogStreams", "logs:PutLogEvents", "logs:GetLogEvents", "logs:FilterLogEvents",
    ]
    resources = ["${local.arn}:logs:${local.region}:${local.account_id}:*"]
  }
}

data "aws_iam_policy_document" "purchase_apigw" {
  statement {
    sid       = "StartTheSaga"
    actions   = ["states:StartExecution"]
    resources = [local.saga_arn_pattern]
  }
}

data "aws_iam_policy_document" "scheduler" {
  statement {
    sid       = "InvokeHoldCleanup"
    actions   = ["lambda:InvokeFunction"]
    resources = ["${local.arn}:lambda:${local.region}:${local.account_id}:function:${var.lambda_function_names["hold-cleanup"]}"]
  }
}

data "aws_iam_policy_document" "saga" {
  source_policy_documents = [data.aws_iam_policy_document.xray.json]

  # The saga's steps are HTTPS calls to the services through the REST API, authenticated by
  # the EventBridge connection's client_credentials token (ticketing/ops).
  statement {
    sid       = "CallServicesThroughTheEdge"
    actions   = ["states:InvokeHTTPEndpoint"]
    resources = [local.saga_arn_pattern]

    condition {
      test     = "StringLike"
      variable = "states:HTTPEndpoint"
      values   = ["https://*.execute-api.${local.region}.amazonaws.com/*"]
    }
  }

  statement {
    sid       = "UseTheConnection"
    actions   = ["events:RetrieveConnectionCredentials"]
    resources = ["${local.arn}:events:${local.region}:${local.account_id}:connection/${var.name_prefix}-*/*"]
  }

  statement {
    sid       = "ReadTheConnectionSecret"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["${local.arn}:secretsmanager:${local.region}:${local.account_id}:secret:events!connection/${var.name_prefix}-*"]
  }

  statement {
    sid       = "EnqueueIssuance"
    actions   = ["sqs:SendMessage"]
    resources = [var.queue_arns["ticket-issuance"]]
  }

  statement {
    sid       = "NotifyPurchaseConfirmed"
    actions   = ["sns:Publish"]
    resources = [var.topic_arns["purchase-confirmed"]]
  }

  # The producer grant D11 calls out: SendMessage and Publish alone are not enough on a CMK.
  statement {
    sid       = "ProduceOnTheKey"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = [local.kms_via["sqs"], local.kms_via["sns"]]
    }
  }

  # The state machine's own data (definition, execution input and history, which carry the
  # buyer's email) is on the CMK (D11). Step Functions encrypts and decrypts it as this role,
  # scoped by the encryption context to this one state machine. No kms:ViaService here: AWS
  # warns it can stop an execution from starting (Step Functions "Data at rest encryption").
  statement {
    sid       = "EncryptOwnExecutionData"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:aws:states:stateMachineArn"
      values   = [local.saga_arn_pattern]
    }
  }

  # With that key on, the execution log events it delivers are encrypted under it as well.
  statement {
    sid       = "EncryptOwnLogDelivery"
    actions   = ["kms:GenerateDataKey"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringLike"
      variable = "kms:EncryptionContext:SourceArn"
      values   = ["${local.arn}:logs:${local.region}:${local.account_id}:*"]
    }
  }

  # Step Functions log delivery goes through the CloudWatch Logs delivery API, which takes no
  # resource (AWS documents "*" for these).
  statement {
    sid = "LogDeliveryHasNoResource"
    actions = [
      "logs:CreateLogDelivery", "logs:DeleteLogDelivery", "logs:DescribeLogGroups",
      "logs:DescribeResourcePolicies", "logs:GetLogDelivery", "logs:ListLogDeliveries",
      "logs:PutResourcePolicy", "logs:UpdateLogDelivery",
    ]
    resources = ["*"]
  }
}
