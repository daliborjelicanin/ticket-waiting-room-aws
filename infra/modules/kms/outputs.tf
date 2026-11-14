output "key_arn" {
  description = "ARN of the customer-managed key."
  value       = aws_kms_key.this.arn
}

output "key_id" {
  description = "Id of the customer-managed key."
  value       = aws_kms_key.this.key_id
}
