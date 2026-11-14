# Outputs. Role ARNs contain the account id: workflows build them from a masked repository
# variable instead of reading them from here (D14).

output "state_bucket" {
  description = "Name of the state bucket (contains the account id; do not paste into committed files)."
  value       = aws_s3_bucket.state.bucket
  sensitive   = true
}

output "ci_role_names" {
  description = "Names of the four CI roles."
  value       = { for k, r in aws_iam_role.ci : k => r.name }
}
