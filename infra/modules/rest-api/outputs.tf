output "rest_api_id" {
  description = "REST API id."
  value       = aws_api_gateway_rest_api.this.id
}

output "root_resource_id" {
  description = "Root resource id."
  value       = aws_api_gateway_rest_api.this.root_resource_id
}

output "execution_arn" {
  description = "Execution ARN (for WAF and permissions in the session stack)."
  value       = aws_api_gateway_rest_api.this.execution_arn
}

output "proxy_resource_id" {
  description = "The /{proxy+} resource, which the session stack's VPC_LINK integration attaches to."
  value       = aws_api_gateway_resource.proxy.id
}

output "proxy_http_method" {
  description = "The /{proxy+} method (ANY)."
  value       = aws_api_gateway_method.proxy.http_method
}

output "invoke_url" {
  description = "https://<rest-api-id>.execute-api.<region>.amazonaws.com/<stage>: TICKET_VERIFY_BASE_URL and the saga's services base URL."
  value       = local.invoke_url
}

output "definition_hash" {
  description = "Hash of everything here a deployment snapshots; part of the session deployment's triggers."
  value       = local.definition_hash
}

output "access_log_group_arn" {
  description = "Access log group ARN for the session stage."
  value       = aws_cloudwatch_log_group.access.arn
}

output "queue_resource_id" {
  description = "The /events/{eventId}/queue resource, which the session stack's queue integrations attach to."
  value       = aws_api_gateway_resource.queue.id
}

output "buyer_entry_paths" {
  description = "Resource paths of the buyer entry points (queue join, purchase): what the WAF buyer rules and method throttling target (D6)."
  value = {
    queue    = aws_api_gateway_resource.queue.path
    purchase = aws_api_gateway_resource.purchase.path
  }
}
