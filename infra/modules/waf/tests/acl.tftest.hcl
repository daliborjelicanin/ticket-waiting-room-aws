# The edge ACL (D6): regional, attached to the stage, logging with the token redacted, and the
# fleet guard keyed on JA4 in count mode until its review. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_wafv2_web_acl.this
    values = { arn = "arn:aws:wafv2:eu-central-1:aws:regional/webacl/twr-test-edge/00000000-0000-0000-0000-000000000000" }
  }
}

variables {
  name           = "twr-test-edge"
  resource_arn   = "arn:aws:apigateway:eu-central-1::/restapis/api123/stages/test"
  log_bucket_arn = "arn:aws:s3:::aws-waf-logs-twr-test-0"
}

run "regional_and_attached_to_the_stage" {
  command = plan

  assert {
    condition     = aws_wafv2_web_acl.this.scope == "REGIONAL"
    error_message = "REGIONAL: the ACL is on a REST stage, not CloudFront."
  }

  assert {
    condition     = aws_wafv2_web_acl_association.this.resource_arn == var.resource_arn
    error_message = "The ACL is associated with the given stage."
  }
}

run "logs_without_the_token" {
  command = plan

  assert {
    condition     = contains(aws_wafv2_web_acl_logging_configuration.this.log_destination_configs, var.log_bucket_arn)
    error_message = "Logs go to the aws-waf-logs-* bucket."
  }

  assert {
    condition = anytrue([for f in aws_wafv2_web_acl_logging_configuration.this.redacted_fields : (
      anytrue([for h in f.single_header : (lower(h.name) == "authorization")])
    )])
    error_message = "The Authorization header is redacted in the logs (D6)."
  }
}

run "fleet_guard_keyed_on_ja4_and_counting" {
  command = plan

  assert {
    condition = anytrue([for r in aws_wafv2_web_acl.this.rule : (
      r.name == "fleet-guard-ja4"
      && length(r.action[0].count) == 1
      && length(r.statement[0].rate_based_statement[0].custom_key[0].ja4_fingerprint) == 1
      && r.statement[0].rate_based_statement[0].custom_key[0].ja4_fingerprint[0].fallback_behavior == "NO_MATCH"
    )])
    error_message = "The fleet guard is a JA4-keyed rate rule in count mode, unfingerprinted requests not matched (D6)."
  }
}

run "bucket_without_the_waf_prefix_refused" {
  command = plan

  variables {
    log_bucket_arn = "arn:aws:s3:::twr-test-waf-logs"
  }

  expect_failures = [var.log_bucket_arn]
}
