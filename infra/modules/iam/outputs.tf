output "role_arns" {
  description = "Role ARN by key: <service>-task, <service>-execution, <function>-lambda, websocket-apigw, purchase-apigw, hold-cleanup-scheduler, saga."
  value       = { for k, r in aws_iam_role.this : k => r.arn }
}
