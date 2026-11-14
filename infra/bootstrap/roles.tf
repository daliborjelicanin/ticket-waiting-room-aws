# The four CI roles (D14), split by what each can damage. Policies come from policy documents
# and are inline, bound to the role's lifecycle (D12).

locals {
  arn_prefix   = "arn:${data.aws_partition.current.partition}"
  iam_prefix   = "${local.arn_prefix}:iam::${local.account_id}"
  project_role = "${local.iam_prefix}:role/${local.prefix}-*"
  ci_role_arns = [for n in values(local.ci_role_names) : "${local.iam_prefix}:role/${n}"]
}

resource "aws_iam_role" "ci" {
  for_each = local.ci_role_names

  name                 = each.value
  assume_role_policy   = data.aws_iam_policy_document.trust[each.key].json
  max_session_duration = 3600
}

# --- shared statements -----------------------------------------------------------------

data "aws_iam_policy_document" "protect_bootstrap" {
  # No CI role may alter what bootstrap owns: itself, its siblings, the boundary, the OIDC
  # provider, or the state bucket's configuration.
  statement {
    sid    = "ProtectBootstrapIdentities"
    effect = "Deny"
    not_actions = [
      "iam:Get*", "iam:List*", "iam:PassRole", "iam:SimulatePrincipalPolicy",
    ]
    resources = concat(local.ci_role_arns, [
      aws_iam_policy.boundary.arn,
      aws_iam_openid_connect_provider.github.arn,
    ])
  }

  statement {
    sid    = "ProtectStateBucketConfiguration"
    effect = "Deny"
    actions = [
      "s3:DeleteBucket*", "s3:PutBucket*", "s3:PutEncryptionConfiguration",
      "s3:PutLifecycleConfiguration", "s3:PutBucketVersioning",
    ]
    resources = [aws_s3_bucket.state.arn]
  }
}

data "aws_iam_policy_document" "state_access" {
  statement {
    sid       = "ListState"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state.arn]
  }

  statement {
    sid       = "ReadWriteState"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.state.arn}/*"]
  }
}

data "aws_iam_policy_document" "read_iam" {
  # Terraform refreshes roles and policies on every plan and apply. PowerUserAccess excludes
  # IAM reads (it allows only iam:ListRoles), so they are granted here, read-only.
  statement {
    sid       = "ReadIamForRefresh"
    actions   = ["iam:Get*", "iam:List*"]
    resources = ["*"]
  }
}

# --- ci-images: push images and record digests, nothing else -----------------------------

data "aws_iam_policy_document" "images" {
  statement {
    sid       = "EcrLoginHasNoResource"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "PushProjectImages"
    actions = [
      "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload",
      "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload",
      "ecr:PutImage", "ecr:UploadLayerPart",
    ]
    resources = ["${local.arn_prefix}:ecr:${var.region}:${local.account_id}:repository/${local.prefix}-*"]
  }

  statement {
    sid       = "RecordDigests"
    actions   = ["ssm:PutParameter", "ssm:GetParameter", "ssm:AddTagsToResource"]
    resources = ["${local.arn_prefix}:ssm:${var.region}:${local.account_id}:parameter/twr/${var.env}/images/*"]
  }
}

# --- tf-plan: read configuration, write only lock files, never read data planes ------------

data "aws_iam_policy_document" "plan" {
  source_policy_documents = [data.aws_iam_policy_document.protect_bootstrap.json]

  statement {
    sid       = "ListState"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state.arn]
  }

  statement {
    sid       = "ReadState"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.state.arn}/*"]
  }

  # use_lockfile takes a lock even for plan: the classic first-CI failure (D14).
  statement {
    sid       = "WriteOnlyLockFiles"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.state.arn}/*.tflock"]
  }

  # plan runs provider code with these credentials, including a PR's data sources, so no
  # data plane is readable (D14). ReadOnlyAccess would otherwise allow all of these.
  statement {
    sid    = "NoDataPlaneReads"
    effect = "Deny"
    actions = [
      "dynamodb:BatchGetItem", "dynamodb:GetItem", "dynamodb:PartiQLSelect", "dynamodb:Query",
      "dynamodb:Scan", "kms:Decrypt", "logs:FilterLogEvents", "logs:GetLogEvents",
      "logs:StartLiveTail", "logs:StartQuery", "secretsmanager:GetSecretValue",
      "sqs:ReceiveMessage",
    ]
    resources = ["*"]
  }

  statement {
    sid           = "NoObjectReadsOutsideState"
    effect        = "Deny"
    actions       = ["s3:GetObject*"]
    not_resources = ["${aws_s3_bucket.state.arn}/*"]
  }
}

# --- tf-apply-foundation: everything in foundation; IAM only under the boundary ------------

data "aws_iam_policy_document" "apply_foundation" {
  source_policy_documents = [
    data.aws_iam_policy_document.protect_bootstrap.json,
    data.aws_iam_policy_document.state_access.json,
    data.aws_iam_policy_document.read_iam.json,
  ]

  statement {
    sid    = "RoleWritesOnlyWithTheBoundary"
    effect = "Allow"
    actions = [
      "iam:AttachRolePolicy", "iam:CreateRole", "iam:DeleteRolePolicy", "iam:DetachRolePolicy",
      "iam:PutRolePermissionsBoundary", "iam:PutRolePolicy",
    ]
    resources = [local.project_role]

    condition {
      test     = "StringEquals"
      variable = "iam:PermissionsBoundary"
      values   = [aws_iam_policy.boundary.arn]
    }
  }

  statement {
    sid    = "ManageProjectRoles"
    effect = "Allow"
    actions = [
      "iam:DeleteRole", "iam:PassRole", "iam:TagRole", "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy", "iam:UpdateRole", "iam:UpdateRoleDescription",
    ]
    resources = [local.project_role]
  }

  statement {
    sid       = "ServiceLinkedRolesAndAccessAnalyzer"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["${local.iam_prefix}:role/aws-service-role/*"]
  }

  statement {
    sid       = "NeverRemoveTheBoundary"
    effect    = "Deny"
    actions   = ["iam:DeleteRolePermissionsBoundary"]
    resources = ["*"]
  }
}

# --- tf-apply-session: create and destroy session resources; no IAM writes ----------------

data "aws_iam_policy_document" "apply_session" {
  source_policy_documents = [
    data.aws_iam_policy_document.protect_bootstrap.json,
    data.aws_iam_policy_document.state_access.json,
    data.aws_iam_policy_document.read_iam.json,
  ]

  statement {
    sid       = "PassProjectRolesToServices"
    actions   = ["iam:PassRole"]
    resources = [local.project_role]
  }

  # PowerUserAccess allows creating service-linked roles for any service; narrow it.
  statement {
    sid       = "OnlyTheServiceLinkedRolesSessionsNeed"
    effect    = "Deny"
    actions   = ["iam:CreateServiceLinkedRole"]
    resources = ["*"]

    condition {
      test     = "StringNotEquals"
      variable = "iam:AWSServiceName"
      values = [
        "ecs.amazonaws.com", "elasticloadbalancing.amazonaws.com", "rds.amazonaws.com",
        "elasticache.amazonaws.com", "ecs.application-autoscaling.amazonaws.com",
      ]
    }
  }
}

locals {
  inline_policies = {
    images        = data.aws_iam_policy_document.images.json
    plan          = data.aws_iam_policy_document.plan.json
    apply_found   = data.aws_iam_policy_document.apply_foundation.json
    apply_session = data.aws_iam_policy_document.apply_session.json
  }

  # AWS-managed baselines, narrowed by the inline statements above.
  managed_policies = {
    plan          = "${local.arn_prefix}:iam::aws:policy/ReadOnlyAccess"
    apply_found   = "${local.arn_prefix}:iam::aws:policy/PowerUserAccess"
    apply_session = "${local.arn_prefix}:iam::aws:policy/PowerUserAccess"
  }
}

resource "aws_iam_role_policy" "ci" {
  for_each = local.inline_policies

  name   = "${aws_iam_role.ci[each.key].name}-policy"
  role   = aws_iam_role.ci[each.key].id
  policy = each.value
}

resource "aws_iam_role_policy_attachment" "ci" {
  for_each = local.managed_policies

  role       = aws_iam_role.ci[each.key].name
  policy_arn = each.value
}
