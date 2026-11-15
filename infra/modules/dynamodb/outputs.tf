output "name" {
  description = "Table name."
  value       = aws_dynamodb_table.this.name
}

output "arn" {
  description = "Table ARN. IAM policies also need \"<arn>/index/*\" for GSI queries (retro §3)."
  value       = aws_dynamodb_table.this.arn
}
