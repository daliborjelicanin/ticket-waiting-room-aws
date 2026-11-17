# The edge's web ACL (D6), on the REST stage. Session-only: it bills by the hour.
#
# Built so far: the D6 spike's shape. One rule, the fleet guard, a rate limit keyed on the JA4
# TLS fingerprint, in count mode, plus logging. The spike's question is whether WAF computes
# JA4 for an ACL on a REST API stage (the announcement was written around CloudFront and ALB):
# the answer is the ja4Fingerprint field in the logs. Still to come in slice 2: the flood and
# credential guards, Bot Control and the anonymous-IP list scoped to queue join and purchase,
# and the managed baseline in count mode on the whole stage.

resource "aws_wafv2_web_acl" "this" {
  name        = var.name
  description = "Edge ACL on the REST stage, D6"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  # Fleet guard (D6): one bot toolkit rotating through many IPs shares one JA4 fingerprint.
  # A request with no fingerprint is not counted against anyone (fallback NO_MATCH).
  rule {
    name     = "fleet-guard-ja4"
    priority = 30

    action {
      count {}
    }

    statement {
      rate_based_statement {
        limit                 = var.fleet_guard_limit
        evaluation_window_sec = 60
        aggregate_key_type    = "CUSTOM_KEYS"

        custom_key {
          ja4_fingerprint {
            fallback_behavior = "NO_MATCH"
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-fleet-guard-ja4"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = var.name
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "this" {
  resource_arn = var.resource_arn
  web_acl_arn  = aws_wafv2_web_acl.this.arn
}

# Logs to S3 (the bucket is foundation's, aws-waf-logs-*, 7-day expiry). The sampled requests in
# the console are a 3-hour window, not a record (D6).
resource "aws_wafv2_web_acl_logging_configuration" "this" {
  resource_arn            = aws_wafv2_web_acl.this.arn
  log_destination_configs = [var.log_bucket_arn]

  # The bearer token never reaches a log (D6).
  redacted_fields {
    single_header {
      name = "authorization"
    }
  }
}
