output "api_id" {
  description = "WebSocket API id."
  value       = aws_apigatewayv2_api.this.id
}

output "execution_arn" {
  description = "Execution ARN, for the notifier's execute-api:ManageConnections."
  value       = aws_apigatewayv2_api.this.execution_arn
}

output "client_url" {
  description = "What browsers open: wss://<api-id>.execute-api.<region>.amazonaws.com/<stage>."
  value       = aws_apigatewayv2_stage.this.invoke_url
}

output "management_endpoint" {
  description = "The notifier's WEBSOCKET_ENDPOINT: the same host over https. Not the SDK default, which is the control plane."
  value       = "${replace(aws_apigatewayv2_api.this.api_endpoint, "wss://", "https://")}/${aws_apigatewayv2_stage.this.name}"
}

output "access_log_group_name" {
  description = "Access log group."
  value       = aws_cloudwatch_log_group.access.name
}
