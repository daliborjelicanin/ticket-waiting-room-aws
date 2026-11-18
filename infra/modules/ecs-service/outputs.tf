output "service_name" {
  description = "ECS service name (the ServiceName dimension of the AWS/ECS metrics)."
  value       = aws_ecs_service.this.name
}

output "task_definition_arn" {
  description = "The task definition revision the service runs."
  value       = aws_ecs_task_definition.this.arn
}
