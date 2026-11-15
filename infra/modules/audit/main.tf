# The audit trail and threat detection (D24, D27).
#
# - CloudTrail: one multi-region trail, management events only (the first copy is free), log
#   file validation, delivered to a private bucket.
# - GuardDuty: foundational detection only, no paid protection plans.
# - Alerts to ops-alarms through EventBridge: root sign-in and IAM changes are recorded in
#   us-east-1 (global services), so those rules live there and forward to this region's
#   default bus; GuardDuty Medium+ and KMS key disable/deletion are regional.

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  trail_name = "${var.name_prefix}-trail"
  trail_arn  = "arn:${local.partition}:cloudtrail:${data.aws_region.current.region}:${local.account_id}:trail/${local.trail_name}"
}

# --- CloudTrail ----------------------------------------------------------------------------

resource "aws_s3_bucket" "trail" {
  bucket        = "${var.name_prefix}-cloudtrail-${local.account_id}"
  force_destroy = false
}

resource "aws_s3_bucket_public_access_block" "trail" {
  bucket = aws_s3_bucket.trail.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "trail" {
  bucket = aws_s3_bucket.trail.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "trail" {
  bucket = aws_s3_bucket.trail.id

  rule {
    id     = "expire-trail-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = var.log_retention_days
    }
  }
}

data "aws_iam_policy_document" "trail_bucket" {
  statement {
    sid       = "CloudTrailAclCheck"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.trail.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }

  statement {
    sid       = "CloudTrailWrite"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.trail.arn}/AWSLogs/${local.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudtrail.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.trail_arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.trail.arn, "${aws_s3_bucket.trail.arn}/*"]

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
}

resource "aws_s3_bucket_policy" "trail" {
  bucket = aws_s3_bucket.trail.id
  policy = data.aws_iam_policy_document.trail_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.trail]
}

resource "aws_cloudtrail" "this" {
  name                          = local.trail_name
  s3_bucket_name                = aws_s3_bucket.trail.id
  is_multi_region_trail         = true
  include_global_service_events = true
  enable_log_file_validation    = true

  depends_on = [aws_s3_bucket_policy.trail]
}

# --- GuardDuty -----------------------------------------------------------------------------

resource "aws_guardduty_detector" "this" {
  enable                       = true
  finding_publishing_frequency = "FIFTEEN_MINUTES"
}

# --- Alerts in this region -------------------------------------------------------------------

locals {
  regional_rules = {
    guardduty-medium-plus = {
      description = "GuardDuty findings of severity Medium (4) and above (D27)."
      pattern = {
        source        = ["aws.guardduty"]
        "detail-type" = ["GuardDuty Finding"]
        detail        = { severity = [{ numeric = [">=", 4] }] }
      }
    }
    kms-disable-or-delete = {
      description = "A KMS key disabled or scheduled for deletion (D24)."
      pattern = {
        source        = ["aws.kms"]
        "detail-type" = ["AWS API Call via CloudTrail"]
        detail        = { eventName = ["DisableKey", "ScheduleKeyDeletion"] }
      }
    }
    # Forwarded from us-east-1 (below): they arrive on this bus with their original shape.
    root-sign-in = {
      description = "Root user sign-in, forwarded from us-east-1 (D24)."
      pattern = {
        "detail-type" = ["AWS Console Sign In via CloudTrail"]
        detail        = { userIdentity = { type = ["Root"] } }
      }
    }
    iam-change = {
      description = "IAM write calls, forwarded from us-east-1 (D24)."
      pattern = {
        source        = ["aws.iam"]
        "detail-type" = ["AWS API Call via CloudTrail"]
        detail        = { eventName = [{ "anything-but" = { prefix = "Get" } }], readOnly = [false] }
      }
    }
  }
}

resource "aws_cloudwatch_event_rule" "regional" {
  for_each = local.regional_rules

  name          = "${var.name_prefix}-${each.key}"
  description   = each.value.description
  event_pattern = jsonencode(each.value.pattern)
}

resource "aws_cloudwatch_event_target" "regional" {
  for_each = local.regional_rules

  rule = aws_cloudwatch_event_rule.regional[each.key].name
  arn  = var.ops_alarms_topic_arn
}

# --- us-east-1: global-service events, forwarded to this region's default bus ---------------

locals {
  # The two rules recorded in us-east-1, with the same description and pattern as their
  # home-region twins. Flat string maps keyed by rule: an attribute of local.map[key] is
  # unresolvable in IntelliJ's HCL plugin (infra/CLAUDE.md).
  us_east_1_rules         = toset(["root-sign-in", "iam-change"])
  rule_description        = { for k, r in local.regional_rules : k => r.description }
  rule_event_pattern_json = { for k, r in local.regional_rules : k => jsonencode(r.pattern) }
}

data "aws_iam_policy_document" "forward_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

data "aws_iam_policy_document" "forward" {
  statement {
    sid       = "PutEventsOnHomeRegionDefaultBus"
    actions   = ["events:PutEvents"]
    resources = ["arn:${local.partition}:events:${data.aws_region.current.region}:${local.account_id}:event-bus/default"]
  }
}

resource "aws_iam_role" "forward" {
  name                 = "${var.name_prefix}-security-events-forwarder"
  assume_role_policy   = data.aws_iam_policy_document.forward_trust.json
  permissions_boundary = var.permissions_boundary_arn
}

resource "aws_iam_role_policy" "forward" {
  name   = "forward-to-home-region"
  role   = aws_iam_role.forward.id
  policy = data.aws_iam_policy_document.forward.json
}

resource "aws_cloudwatch_event_rule" "us_east_1" {
  for_each = local.us_east_1_rules
  provider = aws.us_east_1

  name          = "${var.name_prefix}-${each.key}"
  description   = local.rule_description[each.key]
  event_pattern = local.rule_event_pattern_json[each.key]
}

resource "aws_cloudwatch_event_target" "us_east_1" {
  for_each = local.us_east_1_rules
  provider = aws.us_east_1

  rule     = aws_cloudwatch_event_rule.us_east_1[each.key].name
  arn      = "arn:${local.partition}:events:${data.aws_region.current.region}:${local.account_id}:event-bus/default"
  role_arn = aws_iam_role.forward.arn
}

# --- IAM Access Analyzer -----------------------------------------------------------------
# The free external-access analyzer (D12): it reports any resource policy or trust policy that
# grants access outside this account. A finding fails slice 1's verification. (The unused-
# access analyzer is the paid one, and is not created.)

resource "aws_accessanalyzer_analyzer" "account" {
  analyzer_name = "${var.name_prefix}-external-access"
  type          = "ACCOUNT"
}

# Archive rules: the external access this account is MEANT to have, accepted by rule with its
# reason, so an Active finding always means something new. Each rule matches one principal on
# one set of roles; a different principal, or the same one on another resource, stays Active.
# Each filter was proven against the live findings before it was written (2026-11-15).
# The resource is matched with `contains`: an exact `eq` on a role ARN matched nothing.
# A new rule archives only findings created after it. Findings that already exist need one
# `aws accessanalyzer apply-archive-rule --analyzer-arn <arn> --rule-name <rule>` per rule,
# which Terraform does not call (run 2026-11-15). On a cold rebuild the analyzer starts its
# first scan the moment it exists, before the rules (which depend on it), so run that call
# once after the apply as well.

data "aws_organizations_organization" "this" {}

locals {
  # Each rule: the principal, matched exactly (principal_eq) or by its fixed part
  # (principal_contains, exactly one of the two set), and a fragment of the resource ARN.
  archive_rules = {
    # D14: GitHub Actions assumes the four CI roles through OIDC, scoped by the sub claim.
    github-actions-oidc = {
      principal_key      = "principal.Federated"
      principal_eq       = "arn:${local.partition}:iam::${local.account_id}:oidc-provider/token.actions.githubusercontent.com"
      principal_contains = null
      resource_contains  = ":role/${var.name_prefix}-"
    }
    # D12: the owner's IAM Identity Center sign-in (admin and read-only permission sets). The
    # SAML provider's name carries a generated id (AWSSSO_<id>_DO_NOT_DELETE).
    identity-center-sso = {
      principal_key      = "principal.Federated"
      principal_eq       = null
      principal_contains = ":saml-provider/AWSSSO_"
      resource_contains  = ":role/aws-reserved/sso.amazonaws.com/"
    }
    # D12: break-glass from the management account, Organizations' default member role.
    management-break-glass = {
      principal_key      = "principal.AWS"
      principal_eq       = data.aws_organizations_organization.this.master_account_id
      principal_contains = null
      resource_contains  = ":role/OrganizationAccountAccessRole"
    }
  }
}

resource "aws_accessanalyzer_archive_rule" "intended" {
  for_each = local.archive_rules

  analyzer_name = aws_accessanalyzer_analyzer.account.analyzer_name
  rule_name     = each.key

  filter {
    criteria = each.value.principal_key
    eq       = each.value.principal_eq == null ? null : [each.value.principal_eq]
    contains = each.value.principal_contains == null ? null : [each.value.principal_contains]
  }

  filter {
    criteria = "resource"
    contains = [each.value.resource_contains]
  }
}
