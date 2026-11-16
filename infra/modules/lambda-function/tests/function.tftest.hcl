# A function's silent defaults (retro §3, D7, D15): arm64, image by digest, active tracing, the
# declared log group, and an event source mapping that reports partial failures and is enabled.

mock_provider "aws" {
  override_during = plan
}

# The provider validates the account field of an ARN, and a 12-digit one may not be committed
# (gitleaks, CLAUDE.md). "aws" is a value that field accepts.

variables {
  function_name  = "twr-test-qr-generation"
  role_arn       = "arn:aws:iam::aws:role/twr-test-qr-generation-lambda"
  image_uri      = "registry.example/twr-test-qr-generation-lambda@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  timeout        = 60
  log_group_name = "/aws/lambda/twr-test-qr-generation"
  environment    = { QR_BUCKET = "bucket" }
}

run "function_pins" {
  command = plan

  variables {
    sqs_event_source = null
  }

  assert {
    condition     = aws_lambda_function.this.architectures == tolist(["arm64"]) && aws_lambda_function.this.package_type == "Image"
    error_message = "arm64 container image (D7)."
  }

  assert {
    condition     = aws_lambda_function.this.tracing_config[0].mode == "Active"
    error_message = "Tracing is its own switch per function (retro §3)."
  }

  assert {
    condition     = aws_lambda_function.this.logging_config[0].log_group == "/aws/lambda/twr-test-qr-generation"
    error_message = "Logs go to the declared group."
  }

  assert {
    condition     = aws_cloudwatch_log_group.this.retention_in_days == 3
    error_message = "The declared group has a retention; the implicit one never expires (retro §3)."
  }

  assert {
    condition     = aws_lambda_function.this.memory_size == 1024 && aws_lambda_function.this.timeout == 60
    error_message = "Memory default and the given timeout."
  }

  assert {
    condition     = length(aws_lambda_event_source_mapping.sqs) == 0
    error_message = "No mapping without a queue."
  }
}

run "event_source_mapping_pins" {
  command = plan

  variables {
    sqs_event_source = { queue_arn = "arn:aws:sqs:eu-central-1:aws:twr-test-qr-generation" }
  }

  assert {
    condition     = aws_lambda_event_source_mapping.sqs[0].enabled
    error_message = "enabled = true: a teardown once left a mapping disabled for a slice (retro §3)."
  }

  assert {
    condition     = aws_lambda_event_source_mapping.sqs[0].function_response_types == toset(["ReportBatchItemFailures"])
    error_message = "Without ReportBatchItemFailures the SQSBatchResponse is ignored (retro §3)."
  }

  assert {
    condition = (
      aws_lambda_event_source_mapping.sqs[0].batch_size == 10
      && aws_lambda_event_source_mapping.sqs[0].maximum_batching_window_in_seconds == 0
    )
    error_message = "Batch 10, no batching window, as in Phase 1."
  }

  assert {
    condition     = length(aws_lambda_event_source_mapping.sqs[0].scaling_config) == 0
    error_message = "No maximum_concurrency: it disables the pollers' scale-down (runbook-qr-generation.md)."
  }
}

run "image_by_tag_is_refused" {
  command = plan

  variables {
    image_uri = "registry.example/twr-test-qr-generation-lambda:latest"
  }

  expect_failures = [var.image_uri]
}

run "long_name_is_refused" {
  command = plan

  variables {
    # 69 characters. A literal: IntelliJ cannot parse a `for` inside `${}` (infra/CLAUDE.md).
    function_name = "twr-test-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
  }

  expect_failures = [var.function_name]
}
