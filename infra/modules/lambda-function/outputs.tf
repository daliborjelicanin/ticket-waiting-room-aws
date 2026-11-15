output "function_name" {
  description = "Function name."
  value       = aws_lambda_function.this.function_name
}

output "function_arn" {
  description = "Function ARN."
  value       = aws_lambda_function.this.arn
}

output "log_group_name" {
  description = "The function's log group."
  value       = aws_cloudwatch_log_group.this.name
}
