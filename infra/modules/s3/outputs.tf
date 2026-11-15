output "name" {
  description = "Bucket name (contains the account id: not for committed files)."
  value       = aws_s3_bucket.this.bucket
}

output "arn" {
  description = "Bucket ARN."
  value       = aws_s3_bucket.this.arn
}
