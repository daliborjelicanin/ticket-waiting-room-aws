# One ECR repository per deployable (D24): immutable tags, basic scan on push, keep the last
# few images. Deploys go by digest (D15); immutability means a tag can never be repointed
# under a running task.

locals {
  lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Untagged leftovers of multi-arch or failed pushes"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        # "any" must be the last rule: it matches everything.
        rulePriority = 2
        description  = "Keep the last ${var.keep_images} images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.keep_images
        }
        action = { type = "expire" }
      },
    ]
  })
}

resource "aws_ecr_repository" "this" {
  for_each = toset(var.repositories)

  name                 = "${var.name_prefix}-${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.force_delete

  # Basic scanning: free, CVEs of the OS packages. Enhanced (Inspector) bills per image.
  image_scanning_configuration {
    scan_on_push = true
  }

  # AWS-managed: images hold code, not customer data (D11).
  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy     = local.lifecycle_policy
}

# --- public images through the private registry (pull-through cache) ---------------------------
# A public.ecr.aws image is served from CloudFront, which no gateway endpoint reaches, so every
# task start pulled the collector sidecar through the session NAT: 262 MB in one session's flow
# logs (phase2-plan, slice 2 verify, 2026-11-18). Cached here, its layers come from S3 through
# the gateway endpoint like our own images (D3).
#
# The prefix is mirror-<prefix>, deliberately outside <prefix>-*: ci-images may push to
# <prefix>-* (D14), and must not be able to plant a tag in a mirror before its first pull.
# The repositories are created here, not on first pull, so they carry our settings (immutable,
# scanned, lifecycle, tags) and no role needs ecr:CreateRepository.

locals {
  mirror_prefix = "mirror-${var.name_prefix}"
}

resource "aws_ecr_pull_through_cache_rule" "public" {
  count = length(var.public_mirrors) > 0 ? 1 : 0

  ecr_repository_prefix = local.mirror_prefix
  upstream_registry_url = "public.ecr.aws"
}

resource "aws_ecr_repository" "mirror" {
  for_each = toset(var.public_mirrors)

  name = "${local.mirror_prefix}/${each.key}"
  # Immutable: a version tag, once cached, is never re-pulled from upstream under a running task.
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true # a cache: re-pulled on demand

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "mirror" {
  for_each = aws_ecr_repository.mirror

  repository = each.value.name
  policy     = local.lifecycle_policy
}
