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

output "target_group_arns" {
  description = "Target group ARN per routed service, for ecs-service's load_balancer block."
  value       = { for k, tg in aws_lb_target_group.service : k => tg.arn }
}

output "target_group_arn_suffixes" {
  description = "Target group ARN suffix per routed service: the TargetGroup dimension, and half of the ALBRequestCountPerTarget resource label."
  value       = { for k, tg in aws_lb_target_group.service : k => tg.arn_suffix }
}
