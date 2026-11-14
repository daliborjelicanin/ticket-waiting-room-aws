# ops-alarms: the one topic every alert that needs a human goes to (D16). Slice 0 creates it
# so GuardDuty, the CloudTrail security rules and the budgets have a target; slice 1 adds log
# groups, the dashboard and the foundation alarms to this module.

data "aws_caller_identity" "current" {}

resource "aws_sns_topic" "ops_alarms" {
  name              = "${var.name_prefix}-ops-alarms"
  kms_master_key_id = var.kms_key_arn
}

data "aws_iam_policy_document" "ops_alarms" {
  statement {
    sid       = "AwsAlertSourcesPublish"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.ops_alarms.arn]

    principals {
      type = "Service"
      identifiers = [
        "budgets.amazonaws.com",
        "cloudwatch.amazonaws.com",
        "costalerts.amazonaws.com",
        "events.amazonaws.com",
      ]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "ops_alarms" {
  arn    = aws_sns_topic.ops_alarms.arn
  policy = data.aws_iam_policy_document.ops_alarms.json
}

resource "aws_sns_topic_subscription" "email" {
  topic_arn = aws_sns_topic.ops_alarms.arn
  protocol  = "email"
  endpoint  = var.alert_email
}
