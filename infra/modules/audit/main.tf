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
  us_east_1_rules = {
    root-sign-in = local.regional_rules["root-sign-in"]
    iam-change   = local.regional_rules["iam-change"]
  }
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
  description   = each.value.description
  event_pattern = jsonencode(each.value.pattern)
}

resource "aws_cloudwatch_event_target" "us_east_1" {
  for_each = local.us_east_1_rules
  provider = aws.us_east_1

  rule     = aws_cloudwatch_event_rule.us_east_1[each.key].name
  arn      = "arn:${local.partition}:events:${data.aws_region.current.region}:${local.account_id}:event-bus/default"
  role_arn = aws_iam_role.forward.arn
}
