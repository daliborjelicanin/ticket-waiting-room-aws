# The permissions boundary (D12, D14). Every role foundation creates must carry it, which
# tf-apply-foundation's own policy enforces (roles.tf). A boundary grants nothing; it caps
# what a role's own policies can ever add up to, so a mistaken or malicious policy in a PR
# cannot produce a role that administers IAM or leaves the project's services.

data "aws_iam_policy_document" "boundary" {
  statement {
    sid    = "ProjectServicesOnly"
    effect = "Allow"
    actions = [
      "apigateway:*", "application-autoscaling:*", "cloudwatch:*", "cognito-idp:*",
      "dynamodb:*", "ec2:*NetworkInterface*", "ec2:Describe*", "ecr:*", "ecs:*",
      "elasticache:Connect", "elasticloadbalancing:*", "events:*", "execute-api:*",
      "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:GenerateDataKey*",
      "lambda:*", "logs:*", "rds-db:connect", "s3:*", "secretsmanager:*", "ses:*", "sns:*",
      "sqs:*", "ssm:GetParameter*", "ssmmessages:*", "states:*", "sts:AssumeRole", "xray:*",
    ]
    resources = ["*"]
  }

  # PassRole is the escalation path a boundary exists to close: a role allowed to pass *any*
  # role to a service could hand it one stronger than itself. Only the project's own roles,
  # and only to the services that run project code.
  statement {
    sid       = "PassOnlyProjectRolesToProjectServices"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = ["arn:${data.aws_partition.current.partition}:iam::${local.account_id}:role/${local.prefix}-*"]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = var.pass_role_services
    }
  }

  statement {
    sid    = "NeverAdministerIdentityOrTheOrganization"
    effect = "Deny"
    actions = [
      "account:*", "iam:Add*", "iam:Attach*", "iam:Create*", "iam:Delete*", "iam:Detach*",
      "iam:Put*", "iam:Remove*", "iam:Set*", "iam:Tag*", "iam:Untag*", "iam:Update*",
      "organizations:*", "sso:*",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_policy" "boundary" {
  name        = local.boundary_name
  description = "Permissions boundary for every role foundation creates (D14)."
  policy      = data.aws_iam_policy_document.boundary.json
}
