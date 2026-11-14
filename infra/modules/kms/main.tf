# The one customer-managed key (D11): used by stores holding customer data or credentials,
# and by the ops-alarms topic. Grants to service principals are key-policy statements scoped
# to this account with aws:SourceAccount; IAM principals get use through their own policies.

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_iam_policy_document" "key" {
  # Keep IAM in charge of the key. Without this statement a typo below can make the key
  # unmanageable: the classic KMS lockout (D11).
  statement {
    sid       = "EnableIamPolicies"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  # Publishing to an encrypted SNS topic needs the publisher to use the key; without this
  # statement an alarm or budget alert is dropped with no error at the publisher.
  statement {
    sid       = "AlarmPublishersUseKeyForOpsAlarms"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = var.alarm_publishers
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_kms_key" "this" {
  description             = "${var.name} customer-managed key (D11)"
  enable_key_rotation     = true
  deletion_window_in_days = 7
  policy                  = data.aws_iam_policy_document.key.json
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}"
  target_key_id = aws_kms_key.this.key_id
}
