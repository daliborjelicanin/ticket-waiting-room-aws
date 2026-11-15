# One container-image Lambda with every Phase 1 silent default pinned (retro §3; D7, D22):
# arm64, its own log group declared with a retention, active tracing, and, when it consumes a
# queue, an event source mapping with ReportBatchItemFailures and enabled = true.
#
# Outside the VPC (D22). The role is passed in: every role lives in modules/iam (D12).

resource "aws_cloudwatch_log_group" "this" {
  name              = var.log_group_name
  retention_in_days = var.log_retention_days # the implicit group never expires (retro §3)
}

resource "aws_lambda_function" "this" {
  function_name = var.function_name
  role          = var.role_arn

  package_type  = "Image"
  image_uri     = var.image_uri
  architectures = ["arm64"] # Graviton, ~20% cheaper per GB-second (D7)

  # Lambda scales CPU with memory: 1024 MB is for the CPU share, not the heap (Phase 1 used
  # ~150 MB of it; a smaller size made the JVM's init slower than the work).
  memory_size = var.memory_size
  timeout     = var.timeout

  environment {
    variables = var.environment
  }

  tracing_config {
    mode = "Active"
  }

  # Into the declared group, not /aws/lambda/<name> created implicitly at first invoke.
  logging_config {
    log_format = "Text"
    log_group  = aws_cloudwatch_log_group.this.name
  }
}

resource "aws_lambda_event_source_mapping" "sqs" {
  count = var.sqs_event_source == null ? 0 : 1

  function_name    = aws_lambda_function.this.arn
  event_source_arn = var.sqs_event_source.queue_arn

  # A teardown once left a mapping disabled and nothing noticed for a slice (retro §3).
  enabled = true

  # Without it Lambda ignores the returned SQSBatchResponse, and one bad record redelivers the
  # whole batch, its nine good neighbours to the DLQ with it (retro §3).
  function_response_types = ["ReportBatchItemFailures"]

  batch_size                         = var.sqs_event_source.batch_size
  maximum_batching_window_in_seconds = var.sqs_event_source.batching_window_seconds

  # No scaling_config.maximum_concurrency: setting it disables the pollers' scale-down, so the
  # field that reads like a cost control raises the idle cost (runbook-qr-generation.md).
}
