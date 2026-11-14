output "trail_arn" {
  description = "ARN of the CloudTrail trail."
  value       = aws_cloudtrail.this.arn
}

output "guardduty_detector_id" {
  description = "Id of the GuardDuty detector."
  value       = aws_guardduty_detector.this.id
}
