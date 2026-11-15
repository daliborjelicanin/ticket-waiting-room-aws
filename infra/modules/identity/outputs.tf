output "user_pool_id" {
  description = "User pool id."
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "User pool ARN."
  value       = aws_cognito_user_pool.this.arn
}

output "issuer_uri" {
  description = "JWT issuer, COGNITO_ISSUER_URI for every service."
  value       = "https://${aws_cognito_user_pool.this.endpoint}"
}

output "token_url" {
  description = "OAuth token endpoint for the client_credentials clients."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${data.aws_region.current.region}.amazoncognito.com/oauth2/token"
}

output "web_client_id" {
  description = "Public buyer client id."
  value       = aws_cognito_user_pool_client.web.id
}

output "m2m_client_ids" {
  description = "Client id of each machine client (ops, gate)."
  value       = { for k, c in aws_cognito_user_pool_client.m2m : k => c.id }
}

output "m2m_client_secrets" {
  description = "Client secret of each machine client; reaches the EventBridge connection, never a human (D13)."
  value       = { for k, c in aws_cognito_user_pool_client.m2m : k => c.client_secret }
  sensitive   = true
}
