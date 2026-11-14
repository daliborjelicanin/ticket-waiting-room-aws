# GitHub Actions OIDC (D14). No static keys anywhere.
#
# The repository's OIDC subject is customised to include job_workflow_ref (README.md, set once
# with the GitHub API), so a trust policy names the workflow file, not just the repository.
# GitHub issues the immutable form, with numeric owner and repository ids:
#   repo:<owner>@<id>/<repo>@<id>:<context>:job_workflow_ref:<owner>/<repo>/.github/workflows/<file>@<ref>
# The first OIDC run in slice 4 prints its claims to confirm this shape before any apply.

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # No thumbprint_list: AWS validates GitHub's certificate against its own trusted CAs.
}

locals {
  repo       = var.github_repository
  sub_prefix = var.github_oidc_sub_prefix

  # The sub claim each role trusts. StringLike only where the ref varies (PR merge refs).
  trusted_subjects = {
    images = [
      "${local.sub_prefix}:ref:refs/heads/main:job_workflow_ref:${local.repo}/.github/workflows/images.yml@refs/heads/main",
    ]
    plan = [
      "${local.sub_prefix}:pull_request:job_workflow_ref:${local.repo}/.github/workflows/terraform.yml@refs/pull/*/merge",
      "${local.sub_prefix}:ref:refs/heads/main:job_workflow_ref:${local.repo}/.github/workflows/drift.yml@refs/heads/main",
    ]
    apply_found = [
      "${local.sub_prefix}:environment:foundation:job_workflow_ref:${local.repo}/.github/workflows/terraform.yml@refs/heads/main",
    ]
    apply_session = [
      "${local.sub_prefix}:environment:session:job_workflow_ref:${local.repo}/.github/workflows/session.yml@refs/heads/main",
      "${local.sub_prefix}:environment:session:job_workflow_ref:${local.repo}/.github/workflows/session-reaper.yml@refs/heads/main",
    ]
  }
}

data "aws_iam_policy_document" "trust" {
  for_each = local.trusted_subjects

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = each.value
    }
  }
}
