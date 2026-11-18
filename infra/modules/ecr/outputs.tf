output "repository_urls" {
  description = "Repository URL per deployable (contains the account id: not for committed files)."
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "repository_arns" {
  description = "Repository ARN per deployable."
  value       = { for k, r in aws_ecr_repository.this : k => r.arn }
}

output "mirror_urls" {
  description = "Cached repository URL per public.ecr.aws path (contains the account id: not for committed files)."
  value       = { for k, r in aws_ecr_repository.mirror : k => r.repository_url }
}

output "mirror_arns" {
  description = "Cached repository ARN per public.ecr.aws path."
  value       = { for k, r in aws_ecr_repository.mirror : k => r.arn }
}
