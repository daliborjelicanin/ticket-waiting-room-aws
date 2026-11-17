output "arn" {
  description = "ALB ARN: the REST integration's target (D5)."
  value       = aws_lb.this.arn
}

output "arn_suffix" {
  description = "ARN suffix, the LoadBalancer dimension of the AWS/ApplicationELB metrics."
  value       = aws_lb.this.arn_suffix
}

output "dns_name" {
  description = "Internal DNS name, the host in the REST integration's URI."
  value       = aws_lb.this.dns_name
}

output "listener_arn" {
  description = "The HTTP listener, which the services' path rules attach to."
  value       = aws_lb_listener.http.arn
}
