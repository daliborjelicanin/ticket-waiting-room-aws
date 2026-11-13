# Outputs. Nothing here is read by another root; these are for the owner.

output "readonly_permission_set" {
  description = "Name of the day-to-day permission set to put in ~/.aws/config."
  value       = aws_ssoadmin_permission_set.readonly.name
}
