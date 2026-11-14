output "anomaly_monitor_arn" {
  description = "ARN of the service-dimension cost anomaly monitor."
  value       = aws_ce_anomaly_monitor.services.arn
}
