output "queue_urls" {
  description = "Main queue URL by short name (contains the account id: not for committed files)."
  value       = { for k, q in aws_sqs_queue.main : k => q.id }
}

output "queue_arns" {
  description = "Main queue ARN by short name."
  value       = { for k, q in aws_sqs_queue.main : k => q.arn }
}

output "dlq_arns" {
  description = "DLQ ARN by short name of its main queue (the DLQ-depth alarms, D16)."
  value       = { for k, q in aws_sqs_queue.dlq : k => q.arn }
}

output "visibility_timeouts" {
  description = "Derived visibility timeout by queue, in seconds (the oldest-message alarms, D16)."
  value       = local.visibility_timeout
}

output "topic_arns" {
  description = "Topic ARN by short name."
  value       = { for k, t in aws_sns_topic.this : k => t.arn }
}

output "queue_names" {
  description = "Main queue name by short name (the age alarms, D16)."
  value       = { for k, q in aws_sqs_queue.main : k => q.name }
}

output "dlq_names" {
  description = "Every DLQ's name: by its queue's short name, and \"delivery/<topic>/<queue>\" for a subscription's DLQ (the DLQ alarms, D16)."
  value = merge(
    { for k, q in aws_sqs_queue.dlq : k => q.name },
    { for k, q in aws_sqs_queue.subscription_dlq : "delivery/${k}" => q.name },
  )
}
