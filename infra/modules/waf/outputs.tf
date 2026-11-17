output "arn" {
  description = "Web ACL ARN."
  value       = aws_wafv2_web_acl.this.arn
}

output "name" {
  description = "Web ACL name (the WebACL dimension of the AWS/WAFV2 metrics)."
  value       = aws_wafv2_web_acl.this.name
}
