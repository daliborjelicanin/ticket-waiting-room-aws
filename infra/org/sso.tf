# IAM Identity Center permission sets and assignments (D12).
#
# AdministratorAccess was created in the console; imports.tf adopts it and its two
# assignments. prevent_destroy guards all of them: a destroy of this root must never remove
# the owner's only way in.

resource "aws_ssoadmin_permission_set" "admin" {
  name             = "AdministratorAccess"
  description      = "Bootstrap and break-glass. Slices 0-3 apply foundation with it (D12)."
  instance_arn     = local.sso_instance_arn
  session_duration = "PT8H"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ssoadmin_managed_policy_attachment" "admin" {
  instance_arn       = local.sso_instance_arn
  permission_set_arn = aws_ssoadmin_permission_set.admin.arn
  managed_policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ssoadmin_account_assignment" "admin" {
  for_each = {
    management = local.management_account_id
    twr_dev    = local.twr_dev_account_id
  }

  instance_arn       = local.sso_instance_arn
  permission_set_arn = aws_ssoadmin_permission_set.admin.arn
  principal_type     = "USER"
  principal_id       = data.aws_identitystore_user.owner.user_id
  target_type        = "AWS_ACCOUNT"
  target_id          = each.value

  lifecycle {
    prevent_destroy = true
  }
}

# Day-to-day: read everything, write nothing, plus ECS Exec into the project's tasks (D12).
resource "aws_ssoadmin_permission_set" "readonly" {
  name             = "twr-readonly"
  description      = "Day-to-day in twr-dev: read-only plus ECS Exec into twr-* tasks (D12)."
  instance_arn     = local.sso_instance_arn
  session_duration = "PT8H"
}

resource "aws_ssoadmin_managed_policy_attachment" "readonly" {
  instance_arn       = local.sso_instance_arn
  permission_set_arn = aws_ssoadmin_permission_set.readonly.arn
  managed_policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

data "aws_iam_policy_document" "ecs_exec" {
  statement {
    sid     = "EcsExecIntoProjectTasks"
    actions = ["ecs:ExecuteCommand"]
    resources = [
      "arn:aws:ecs:${var.region}:${local.twr_dev_account_id}:cluster/twr-*",
      "arn:aws:ecs:${var.region}:${local.twr_dev_account_id}:task/twr-*/*",
    ]
  }
}

resource "aws_ssoadmin_permission_set_inline_policy" "readonly" {
  instance_arn       = local.sso_instance_arn
  permission_set_arn = aws_ssoadmin_permission_set.readonly.arn
  inline_policy      = data.aws_iam_policy_document.ecs_exec.json
}

resource "aws_ssoadmin_account_assignment" "readonly" {
  instance_arn       = local.sso_instance_arn
  permission_set_arn = aws_ssoadmin_permission_set.readonly.arn
  principal_type     = "USER"
  principal_id       = data.aws_identitystore_user.owner.user_id
  target_type        = "AWS_ACCOUNT"
  target_id          = local.twr_dev_account_id

  # A permission set is provisioned with its policies; assign only once they exist.
  depends_on = [
    aws_ssoadmin_managed_policy_attachment.readonly,
    aws_ssoadmin_permission_set_inline_policy.readonly,
  ]
}
