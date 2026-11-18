# The edge's web ACL (D6), on the REST stage. Session-only: it bills by the hour.
#
# Evaluation order, lowest priority first:
#   0   loadtest-allow       the runner's address, for the concurrency proof only (optional)
#   1   buyer-entry          labels POST queue join and POST purchase; counts, never blocks
#   10  ip-reputation   ┐
#   20  known-bad-inputs├─   AWS baseline on the whole stage, count mode until reviewed
#   30  common          ┘    against real traffic, the saga's own included (D5)
#   40  flood-guard     ┐
#   50  credential-guard│    buyer rules, each scoped down to the buyer-entry label, so none of
#   60  anonymous-ip    │    them can ever touch a path the saga calls (it comes from AWS
#   70  bot-control     ┘    addresses, which is exactly what the anonymous-IP list blocks)
#
# Bot Control is last because it is billed per request it analyses: everything blocked
# before it is not paid for twice.
#
# No fleet guard: WAF computes no JA4 fingerprint for an ACL on a REST stage (D6 spike,
# 2026-11-17). It waits for CloudFront.

locals {
  # The buyer entry points as one regex over the URI WAF sees, which carries the stage:
  # ^/dev/events/[^/]+/(queue|purchase)$. Built from foundation's resource paths, so a path
  # that moves in API Gateway moves here too.
  entry_patterns = [for p in var.buyer_entry_paths : replace(p, "/\\{[^}]+\\}/", "[^/]+")]
  buyer_entry    = "^/${var.stage_name}(${join("|", local.entry_patterns)})$"

  # Label the buyer-entry rule adds; every buyer rule scopes down to it.
  buyer_label = "twr:buyer-entry"

  # Flat maps keyed alike, not a map of objects: IntelliJ cannot resolve
  # local.map[key].attr (infra/CLAUDE.md).
  baseline_group = {
    ip-reputation    = "AWSManagedRulesAmazonIpReputationList"
    known-bad-inputs = "AWSManagedRulesKnownBadInputsRuleSet"
    common           = "AWSManagedRulesCommonRuleSet"
  }
  baseline_priority = {
    ip-reputation    = 10
    known-bad-inputs = 20
    common           = 30
  }
}

resource "aws_wafv2_ip_set" "loadtest" {
  # Whether an address is set is not secret; the address is (sensitive var).
  count = nonsensitive(var.loadtest_allow_cidr == null) ? 0 : 1

  name               = "${var.name}-loadtest"
  description        = "The load test runner, for one proof run only, D6"
  scope              = "REGIONAL"
  ip_address_version = "IPV4"
  addresses          = [var.loadtest_allow_cidr]
}

resource "aws_wafv2_web_acl" "this" {
  name        = var.name
  description = "Edge ACL on the REST stage, D6"
  scope       = "REGIONAL"

  default_action {
    allow {}
  }

  # The concurrency proof: k6 from one machine is what the buyer rules exist to stop, so for
  # that run its address is allowed before them. Passed in at apply time, never committed (D6).
  dynamic "rule" {
    # A plain list, not the resource: <iterator>.value.<attr> does not resolve in IntelliJ.
    for_each = nonsensitive(var.loadtest_allow_cidr == null) ? [] : ["loadtest"]

    content {
      name     = "loadtest-allow"
      priority = 0

      action {
        allow {}
      }

      statement {
        ip_set_reference_statement {
          arn = aws_wafv2_ip_set.loadtest[0].arn
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.name}-loadtest-allow"
        sampled_requests_enabled   = true
      }
    }
  }

  rule {
    name     = "buyer-entry"
    priority = 1

    action {
      count {}
    }

    rule_label {
      name = local.buyer_label
    }

    statement {
      and_statement {
        statement {
          byte_match_statement {
            search_string         = "POST"
            positional_constraint = "EXACTLY"

            field_to_match {
              method {}
            }

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }

        statement {
          regex_match_statement {
            regex_string = local.buyer_entry

            field_to_match {
              uri_path {}
            }

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-buyer-entry"
      sampled_requests_enabled   = true
    }
  }

  # --- baseline on the whole stage --------------------------------------------------------------

  dynamic "rule" {
    for_each = local.baseline_group

    content {
      name     = rule.key
      priority = local.baseline_priority[rule.key]

      # count: the group's own block actions are reported, not applied (D6, review first).
      override_action {
        dynamic "count" {
          for_each = var.baseline_mode == "count" ? [1] : []
          content {}
        }

        dynamic "none" {
          for_each = var.baseline_mode == "block" ? [1] : []
          content {}
        }
      }

      statement {
        managed_rule_group_statement {
          vendor_name = "AWS"
          name        = rule.value
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = "${var.name}-${rule.key}"
        sampled_requests_enabled   = true
      }
    }
  }

  # --- buyer rules, queue join and purchase only -------------------------------------------------

  # Flood guard: one host hammering. Generous, because a carrier's CGNAT or a venue's Wi-Fi
  # puts hundreds of real buyers behind one address (D6).
  rule {
    name     = "flood-guard"
    priority = 40

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.flood_guard_limit
        evaluation_window_sec = 60
        aggregate_key_type    = "IP"

        scope_down_statement {
          label_match_statement {
            scope = "LABEL"
            key   = local.buyer_label
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-flood-guard"
      sampled_requests_enabled   = true
    }
  }

  # Credential guard: one token used faster than a human can, whatever IPs it rotates
  # through. WAF cannot read the JWT's claims; the per-account ticket limit stays in the
  # application, where the account is known (D6).
  rule {
    name     = "credential-guard"
    priority = 50

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit                 = var.credential_guard_limit
        evaluation_window_sec = 60
        aggregate_key_type    = "CUSTOM_KEYS"

        custom_key {
          header {
            name = "authorization"

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }

        scope_down_statement {
          label_match_statement {
            scope = "LABEL"
            key   = local.buyer_label
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-credential-guard"
      sampled_requests_enabled   = true
    }
  }

  # Hosting providers and VPNs, where bot fleets run. Never on the saga's paths: the label
  # covers buyer entry only (D6).
  rule {
    name     = "anonymous-ip"
    priority = 60

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAnonymousIpList"

        scope_down_statement {
          label_match_statement {
            scope = "LABEL"
            key   = local.buyer_label
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-anonymous-ip"
      sampled_requests_enabled   = true
    }
  }

  # HTTP libraries and known automation, at the common level (D6). Targeted-level and
  # Challenge/CAPTCHA need a browser frontend, which does not exist yet.
  rule {
    name     = "bot-control"
    priority = 70

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesBotControlRuleSet"

        managed_rule_group_configs {
          aws_managed_rules_bot_control_rule_set {
            inspection_level = "COMMON"
          }
        }

        scope_down_statement {
          label_match_statement {
            scope = "LABEL"
            key   = local.buyer_label
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-bot-control"
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
