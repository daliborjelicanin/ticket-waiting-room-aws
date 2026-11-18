# The edge ACL (D6): regional, on the stage, logging with the token redacted; the buyer rules
# scoped to POST queue join and purchase through one label, so none can reach the saga's
# paths; the baseline in count mode until reviewed. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_wafv2_web_acl.this
    values = { arn = "arn:aws:wafv2:eu-central-1:aws:regional/webacl/twr-test-edge/00000000-0000-0000-0000-000000000000" }
  }

  override_resource {
    target = aws_wafv2_ip_set.loadtest
    values = { arn = "arn:aws:wafv2:eu-central-1:aws:regional/ipset/twr-test-edge-loadtest/00000000-0000-0000-0000-000000000000" }
  }
}

variables {
  name              = "twr-test-edge"
  resource_arn      = "arn:aws:apigateway:eu-central-1::/restapis/api123/stages/test"
  log_bucket_arn    = "arn:aws:s3:::aws-waf-logs-twr-test-0"
  stage_name        = "test"
  buyer_entry_paths = ["/events/{eventId}/queue", "/events/{eventId}/purchase"]
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

run "buyer_entry_is_post_to_exactly_queue_and_purchase" {
  command = plan

  assert {
    condition     = local.buyer_entry == "^/test(/events/[^/]+/queue|/events/[^/]+/purchase)$"
    error_message = "The buyer-entry regex is anchored, carries the stage, and covers exactly the published paths."
  }

  # What the regex means, on the paths that must and must not match.
  assert {
    condition = (
      can(regex(local.buyer_entry, "/test/events/e1/queue"))
      && can(regex(local.buyer_entry, "/test/events/e1/purchase"))
      && !can(regex(local.buyer_entry, "/test/events/e1/queue/admit"))
      && !can(regex(local.buyer_entry, "/test/events/e1/seats/A1/hold"))
      && !can(regex(local.buyer_entry, "/test/orders/o1/payment"))
    )
    error_message = "Queue join and purchase match; admit, the saga's hold and payment calls do not."
  }

  assert {
    condition = anytrue([for r in aws_wafv2_web_acl.this.rule : (
      r.name == "buyer-entry" && try(length(r.action[0].count), 0) == 1 && anytrue([for l in r.rule_label : (l.name == local.buyer_label)])
    )])
    error_message = "buyer-entry only counts and labels; it never blocks."
  }

  assert {
    condition = anytrue([for r in aws_wafv2_web_acl.this.rule : (
      r.name == "buyer-entry" && anytrue([for st in try(r.statement[0].and_statement[0].statement, []) : (
        try(st.byte_match_statement[0].search_string, "") == "POST"
        && try(st.byte_match_statement[0].positional_constraint, "") == "EXACTLY"
        && length(try(st.byte_match_statement[0].field_to_match[0].method, [])) == 1
      )])
    )])
    error_message = "Only POST is buyer entry: the GET position poll is not rate-limited as a join (D6)."
  }
}

run "every_buyer_rule_is_scoped_to_the_label" {
  command = plan

  assert {
    condition = alltrue([for r in aws_wafv2_web_acl.this.rule : (
      length(r.statement[0].rate_based_statement) == 0
      || try(r.statement[0].rate_based_statement[0].scope_down_statement[0].label_match_statement[0].key, "") == local.buyer_label
    )])
    error_message = "Every rate rule is scoped down to buyer entry."
  }

  assert {
    condition = alltrue([for r in aws_wafv2_web_acl.this.rule : (
      !contains(["anonymous-ip", "bot-control"], r.name)
      || try(r.statement[0].managed_rule_group_statement[0].scope_down_statement[0].label_match_statement[0].key, "") == local.buyer_label
    )])
    error_message = "Anonymous-IP and Bot Control are scoped to buyer entry: never to the saga's paths, which come from AWS addresses (D6)."
  }

  assert {
    condition = anytrue([for r in aws_wafv2_web_acl.this.rule : (
      r.name == "bot-control"
      && try(r.statement[0].managed_rule_group_statement[0].managed_rule_group_configs[0].aws_managed_rules_bot_control_rule_set[0].inspection_level, "") == "COMMON"
    )])
    error_message = "Bot Control at the common level (D6)."
  }
}

run "the_buyer_rules_block" {
  command = plan

  assert {
    condition = alltrue([for r in aws_wafv2_web_acl.this.rule : (
      !contains(["flood-guard", "credential-guard"], r.name) || try(length(r.action[0].block), 0) == 1
    )])
    error_message = "Flood and credential guards block."
  }

  assert {
    condition = alltrue([for r in aws_wafv2_web_acl.this.rule : (
      !contains(["anonymous-ip", "bot-control"], r.name) || try(length(r.override_action[0].none), 0) == 1
    )])
    error_message = "Anonymous-IP and Bot Control are enforced, not counted."
  }

  assert {
    condition     = !anytrue([for r in aws_wafv2_web_acl.this.rule : (strcontains(r.name, "ja4"))])
    error_message = "No JA4 rule: WAF computes no JA4 on a REST stage (D6 spike, 2026-11-17)."
  }
}

run "baseline_counts_by_default" {
  command = plan

  assert {
    condition = length([for r in aws_wafv2_web_acl.this.rule : r.name if(
      contains(["ip-reputation", "known-bad-inputs", "common"], r.name) && try(length(r.override_action[0].count), 0) == 1
    )]) == 3
    error_message = "All three baseline groups are in count mode until reviewed (D6)."
  }

  assert {
    condition     = length(aws_wafv2_ip_set.loadtest) == 0 && !anytrue([for r in aws_wafv2_web_acl.this.rule : (r.name == "loadtest-allow")])
    error_message = "No load-test allow rule unless an address is given."
  }
}

run "baseline_blocks_when_switched" {
  command = plan

  variables {
    baseline_mode = "block"
  }

  assert {
    condition = length([for r in aws_wafv2_web_acl.this.rule : r.name if(
      contains(["ip-reputation", "known-bad-inputs", "common"], r.name) && try(length(r.override_action[0].none), 0) == 1
    )]) == 3
    error_message = "baseline_mode = block enforces the three groups."
  }
}

run "loadtest_runner_allowed_first" {
  command = plan

  variables {
    loadtest_allow_cidr = "203.0.113.7/32"
  }

  assert {
    condition = anytrue([for r in aws_wafv2_web_acl.this.rule : (
      r.name == "loadtest-allow" && r.priority == 0 && try(length(r.action[0].allow), 0) == 1
    )])
    error_message = "The runner's address is allowed before every other rule (D6)."
  }
}

run "bucket_without_the_waf_prefix_refused" {
  command = plan

  variables {
    log_bucket_arn = "arn:aws:s3:::twr-test-waf-logs"
  }

  expect_failures = [var.log_bucket_arn]
}
