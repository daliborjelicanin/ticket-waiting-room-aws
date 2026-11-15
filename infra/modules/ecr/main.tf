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
