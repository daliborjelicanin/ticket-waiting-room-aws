# Outputs. Values other roots need are published as SSM parameters instead (D2).

output "invoke_url" {
  description = "The REST stage's invoke URL, for the session's checks (contains the API id: print it, never commit it). null with enable_alb off."
  value       = one(module.rest_stage[*].invoke_url)
}

output "migration_task_families" {
  description = "Database => migration task definition family, for infra/scripts/migrate.sh (D10). Empty with enable_db off or no image pushed."
  value       = { for db, m in module.migration : db => m.family }
}
