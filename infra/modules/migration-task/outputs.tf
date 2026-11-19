output "arn" {
  description = "ARN of this revision of the task definition."
  value       = aws_ecs_task_definition.this.arn
}

output "family" {
  description = "Task definition family: what migrate.sh runs (its latest ACTIVE revision)."
  value       = aws_ecs_task_definition.this.family
}

output "container_name" {
  description = "The one container, whose exit code is the migration's result."
  value       = "migrate"
}
