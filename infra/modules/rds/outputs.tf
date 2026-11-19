output "address" {
  description = "Endpoint host (names the instance: not for committed files)."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Endpoint port."
  value       = aws_db_instance.this.port
}

output "arn" {
  description = "Instance ARN; the managed master secret is tagged with it."
  value       = aws_db_instance.this.arn
}

output "master_secret_arn" {
  description = "The RDS-managed master secret, for the migration task only (D10)."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "service_secret_arns" {
  description = "Per-service login secret ARN, by role; a service takes <arn>:password:: through valueFrom (D13)."
  value       = { for k, s in aws_secretsmanager_secret.service : k => s.arn }
}
