output "ops_alarms_topic_arn" {
  description = "ARN of the ops-alarms SNS topic."
  value       = aws_sns_topic.ops_alarms.arn
}
