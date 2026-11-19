output "primary_endpoint" {
  description = "Primary endpoint host (names the cluster: not for committed files)."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "Port."
  value       = aws_elasticache_replication_group.this.port
}
