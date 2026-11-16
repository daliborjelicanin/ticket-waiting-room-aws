# Repositories (D15, D24): immutable tags, scan on push, and a lifecycle that keeps the last
# images. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan
}

variables {
  name_prefix  = "twr-test"
  repositories = ["seat-inventory-service", "hold-cleanup-lambda"]
}

run "repositories_are_immutable_and_scanned" {
  command = plan

  assert {
    condition = alltrue([for r in values(aws_ecr_repository.this) : (
      r.image_tag_mutability == "IMMUTABLE"
      && r.image_scanning_configuration[0].scan_on_push
      && !r.force_delete
    )])
    error_message = "IMMUTABLE tags, scan on push, and no force_delete by default (D24)."
  }

  assert {
    condition     = toset([for r in values(aws_ecr_repository.this) : r.name]) == toset(["twr-test-seat-inventory-service", "twr-test-hold-cleanup-lambda"])
    error_message = "Repositories are <prefix>-<module>: ci-images may push only to <prefix>-* (D14)."
  }
}

run "lifecycle_keeps_the_last_three" {
  command = plan

  assert {
    condition = alltrue([for p in values(aws_ecr_lifecycle_policy.this) : (
      jsondecode(p.policy).rules[0].selection.tagStatus == "untagged"
      && jsondecode(p.policy).rules[0].selection.countNumber == 1
      && jsondecode(p.policy).rules[1].selection.tagStatus == "any"
      && jsondecode(p.policy).rules[1].selection.countType == "imageCountMoreThan"
      && jsondecode(p.policy).rules[1].selection.countNumber == 3
    )])
    error_message = "Untagged images expire after a day; the last 3 images are kept, \"any\" as the last rule (D24)."
  }
}
