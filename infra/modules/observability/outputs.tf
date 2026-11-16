output "ops_alarms_topic_arn" {
  description = "ARN of the ops-alarms SNS topic."
  value       = aws_sns_topic.ops_alarms.arn
}

output "collector_config_parameter_name" {
  description = "SSM parameter holding the collector configuration."
  value       = aws_ssm_parameter.collector_config.name
}

output "dashboard_name" {
  description = "CloudWatch dashboard name."
  value       = aws_cloudwatch_dashboard.this.dashboard_name
}

output "alarm_names" {
  description = "Every foundation alarm's name."
  value = concat(
    [for a in aws_cloudwatch_metric_alarm.dlq : a.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.queue_age : a.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.lambda : a.alarm_name],
    [aws_cloudwatch_metric_alarm.admission_publish.alarm_name],
  )
}
