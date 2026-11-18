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

run "public_images_cached_outside_the_ci_push_scope" {
  command = plan

  variables {
    public_mirrors = ["aws-observability/aws-otel-collector"]
  }

  assert {
    condition = (
      aws_ecr_pull_through_cache_rule.public[0].upstream_registry_url == "public.ecr.aws"
      && aws_ecr_pull_through_cache_rule.public[0].ecr_repository_prefix == "mirror-twr-test"
    )
    error_message = "public.ecr.aws is cached under mirror-<prefix>."
  }

  assert {
    condition = (
      aws_ecr_repository.mirror["aws-observability/aws-otel-collector"].name == "mirror-twr-test/aws-observability/aws-otel-collector"
      && !startswith(aws_ecr_repository.mirror["aws-observability/aws-otel-collector"].name, "twr-test-")
    )
    error_message = "Mirrors sit outside <prefix>-*, which ci-images may push to (D14)."
  }

  assert {
    condition = alltrue([for r in values(aws_ecr_repository.mirror) : (
      r.image_tag_mutability == "IMMUTABLE" && r.image_scanning_configuration[0].scan_on_push
    )])
    error_message = "A cached tag is immutable and scanned, like our own images."
  }
}

run "no_mirrors_no_cache_rule" {
  command = plan

  assert {
    condition     = length(aws_ecr_pull_through_cache_rule.public) == 0 && length(aws_ecr_repository.mirror) == 0
    error_message = "Without public_mirrors, no registry-wide cache rule."
  }
}
