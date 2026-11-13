# Service control policies on the workloads OU (D12). They bound every principal in twr-dev,
# the CI roles and the owner's admin session included; they never grant anything.
# Requires the SCP policy type enabled on the root, a one-time step (README.md).
#
# Two policies, not four: an OU takes at most five, and FullAWSAccess already uses one.

locals {
  # Break-glass principals: the role the management account steps in with, and the owner's
  # admin permission set. Everything else, CI included, is bound by the protections below.
  break_glass_principals = [
    "arn:aws:iam::*:role/OrganizationAccountAccessRole",
    "arn:aws:iam::*:role/aws-reserved/sso.amazonaws.com/*/AWSReservedSSO_AdministratorAccess_*",
  ]
}

data "aws_iam_policy_document" "guardrails" {
  statement {
    sid       = "DenyLeavingTheOrganization"
    effect    = "Deny"
    actions   = ["organizations:LeaveOrganization"]
    resources = ["*"]
  }

  # Defence in depth beside centralised root access: member accounts have no root
  # credentials, so this only matters if they are ever recovered. Root sessions started from
  # the management account with sts:AssumeRoot (task-scoped, admin-only) stay allowed: an
  # unconditional deny blocked them too, verified 2026-11-13, which removed the break-glass.
  statement {
    sid       = "DenyMemberRootUserExceptCentralisedSessions"
    effect    = "Deny"
    actions   = ["*"]
    resources = ["*"]

    condition {
      test     = "ArnLike"
      variable = "aws:PrincipalArn"
      values   = ["arn:aws:iam::*:root"]
    }

    condition {
      test     = "BoolIfExists"
      variable = "aws:AssumedRoot"
      values   = ["false"]
    }
  }

  # A leaked pipeline credential must not be able to blind the audit trail (D24, D27).
  # Creating the trail and detector stays allowed, so foundation can build them.
  statement {
    sid    = "ProtectAuditTrailAndThreatDetection"
    effect = "Deny"
    actions = [
      "cloudtrail:DeleteTrail",
      "cloudtrail:PutEventSelectors",
      "cloudtrail:StopLogging",
      "cloudtrail:UpdateTrail",
      "guardduty:DeleteDetector",
      "guardduty:DisassociateFromAdministratorAccount",
      "guardduty:UpdateDetector",
    ]
    resources = ["*"]

    condition {
      test     = "ArnNotLike"
      variable = "aws:PrincipalArn"
      values   = local.break_glass_principals
    }
  }
}

# Only the home region and us-east-1 (global services' home, and the IAM/root-sign-in
# EventBridge rules of D24). Global services are exempt by NotAction: their calls are
# signed for us-east-1 or carry no region.
data "aws_iam_policy_document" "regions" {
  statement {
    sid    = "DenyOutsideAllowedRegions"
    effect = "Deny"
    not_actions = [
      "a4b:*", "account:*", "acm:*", "aws-marketplace-management:*", "aws-marketplace:*",
      "aws-portal:*", "billing:*", "budgets:*", "ce:*", "chime:*", "cloudfront:*", "config:*",
      "cur:*", "directconnect:*", "ec2:DescribeRegions", "ec2:DescribeTransitGateways",
      "ec2:DescribeVpnGateways", "fms:*", "freetier:*", "globalaccelerator:*", "health:*",
      "iam:*", "identitystore:*", "importexport:*", "kms:*", "networkmanager:*",
      "notifications:*", "organizations:*", "pricing:*", "route53-recovery-cluster:*",
      "route53-recovery-control-config:*", "route53-recovery-readiness:*", "route53:*",
      "route53domains:*", "s3:GetAccountPublic*", "s3:ListAllMyBuckets",
      "s3:ListMultiRegionAccessPoints", "s3:PutAccountPublic*", "shield:*", "sso:*", "sts:*",
      "support:*", "trustedadvisor:*", "waf-regional:*", "waf:*", "wafv2:*",
      "wellarchitected:*",
    ]
    resources = ["*"]

    condition {
      test     = "StringNotEquals"
      variable = "aws:RequestedRegion"
      values   = [var.region, "us-east-1"]
    }

    condition {
      test     = "ArnNotLike"
      variable = "aws:PrincipalArn"
      values   = ["arn:aws:iam::*:role/OrganizationAccountAccessRole"]
    }
  }
}

resource "aws_organizations_policy" "guardrails" {
  name        = "twr-workloads-guardrails"
  description = "No leaving the org, no member root, no disabling CloudTrail/GuardDuty except break-glass (D12)."
  type        = "SERVICE_CONTROL_POLICY"
  # Minified: an SCP's 5,120-character limit counts whitespace.
  content = jsonencode(jsondecode(data.aws_iam_policy_document.guardrails.json))
}

resource "aws_organizations_policy" "regions" {
  name        = "twr-workloads-regions"
  description = "Only ${var.region} and us-east-1 (D12)."
  type        = "SERVICE_CONTROL_POLICY"
  content     = jsonencode(jsondecode(data.aws_iam_policy_document.regions.json))
}

resource "aws_organizations_policy_attachment" "workloads" {
  for_each = {
    guardrails = aws_organizations_policy.guardrails.id
    regions    = aws_organizations_policy.regions.id
  }

  policy_id = each.value
  target_id = local.workloads_ou_id
}
