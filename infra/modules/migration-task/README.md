# migration-task

One ECS task definition that runs a service image in its `migrate` profile (`migration-starter`): as the RDS master it creates the service's database and login role, sets the role's password from the service secret, runs Flyway, grants the role DML only, and exits 0 or 1 (D9, D10, D13). A task definition and nothing else: no ECS service, no load balancer, no scaling. `infra/scripts/migrate.sh` runs it before the service is updated. Hardened like `ecs-service`'s containers (D7): ARM64, read-only root with a writable `/tmp`, every capability dropped, awslogs non-blocking. TLS is `sslmode=require`; the server certificate is not verified (no RDS CA bundle in the image yet).

<!-- BEGIN_TF_DOCS -->
## Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | ~> 1.17 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.73 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.73.0 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [aws_ecs_task_definition.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_task_definition) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_app_secret_arn"></a> [app\_secret\_arn](#input\_app\_secret\_arn) | The service role's secret (modules/rds service\_secret\_arns): the role to create and the password to set on it (D13). | `string` | n/a | yes |
| <a name="input_cpu"></a> [cpu](#input\_cpu) | Task CPU units. Half a vCPU: the whole Spring context starts (Hibernate validates the entities after Flyway), and at 0.25 vCPU that alone is about a minute (D7). | `number` | `512` | no |
| <a name="input_database"></a> [database](#input\_database) | The service's database, created by the migration if missing: checkout or ticketing (D9). | `string` | n/a | yes |
| <a name="input_db_address"></a> [db\_address](#input\_db\_address) | The RDS instance's DNS name (modules/rds address). | `string` | n/a | yes |
| <a name="input_db_port"></a> [db\_port](#input\_db\_port) | The RDS instance's port. | `number` | `5432` | no |
| <a name="input_execution_role_arn"></a> [execution\_role\_arn](#input\_execution\_role\_arn) | The migrate execution role (modules/iam): pulls the service image, writes the log stream, resolves the master and the service secret. | `string` | n/a | yes |
| <a name="input_image_uri"></a> [image\_uri](#input\_image\_uri) | The service's own image, by digest (D15): the migration is the image's migrate profile, not a separate tool (D10). | `string` | n/a | yes |
| <a name="input_log_group_name"></a> [log\_group\_name](#input\_log\_group\_name) | Foundation's /twr/<env>/ecs/migrate log group (D25). | `string` | n/a | yes |
| <a name="input_master_secret_arn"></a> [master\_secret\_arn](#input\_master\_secret\_arn) | The RDS-managed master secret (modules/rds master\_secret\_arn). The migration is the only thing that runs as master (D9, D10). | `string` | n/a | yes |
| <a name="input_memory"></a> [memory](#input\_memory) | Task memory in MiB. 1 GB, as the services run with: the same context, no collector beside it. | `number` | `1024` | no |
| <a name="input_name"></a> [name](#input\_name) | Task definition family, e.g. twr-dev-migrate-checkout. infra/scripts/migrate.sh runs the family's latest revision. | `string` | n/a | yes |
| <a name="input_region"></a> [region](#input\_region) | Region of the log group. | `string` | n/a | yes |
| <a name="input_task_role_arn"></a> [task\_role\_arn](#input\_task\_role\_arn) | The migrate task role (modules/iam): nothing in AWS, Flyway runs over a password. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_arn"></a> [arn](#output\_arn) | ARN of this revision of the task definition. |
| <a name="output_container_name"></a> [container\_name](#output\_container\_name) | The one container, whose exit code is the migration's result. |
| <a name="output_family"></a> [family](#output\_family) | Task definition family: what migrate.sh runs (its latest ACTIVE revision). |
<!-- END_TF_DOCS -->
