output "stage_arn" {
  description = "Stage ARN, which the WAF web ACL is associated with (D6)."
  value       = aws_api_gateway_stage.this.arn
}

output "invoke_url" {
  description = "The stage's invoke URL (contains the API id: print it, never commit it)."
  value       = aws_api_gateway_stage.this.invoke_url
}

output "vpc_link_id" {
  description = "VPC Link v2 id."
  value       = aws_apigatewayv2_vpc_link.this.id
}
