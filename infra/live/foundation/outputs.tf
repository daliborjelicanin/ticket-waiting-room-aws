# Outputs. Values other roots need are published as SSM parameters instead (D2); slice 1
# adds them.

output "ops_alarms_topic_arn" {
  description = "ARN of the ops-alarms topic (contains the account id; not for committed files)."
  value       = module.observability.ops_alarms_topic_arn
  sensitive   = true
}
