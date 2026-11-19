# rds

The session's Postgres (D9) and its per-service logins (D13): private in the isolated db subnets, TLS only (`rds.force_ssl`), storage and the RDS-managed master secret on the CMK (D11), a migration-only master (D10). One Secrets Manager secret per service role, its password minted as an ephemeral `random_password` and written with `secret_string_wo`, so it never reaches state or a plan; bump `service_secret_version` to rotate. Dev values by default (no backups, no snapshot, no deletion protection, single AZ), the production values one variable each (D24).

<!-- BEGIN_TF_DOCS -->
## Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | ~> 1.17 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.73 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.7 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.73.0 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [aws_db_instance.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/db_instance) | resource |
| [aws_db_parameter_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/db_parameter_group) | resource |
| [aws_secretsmanager_secret.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret_version.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_allocated_storage_gb"></a> [allocated\_storage\_gb](#input\_allocated\_storage\_gb) | gp3 storage in GB (20 is the minimum). | `number` | `20` | no |
| <a name="input_availability_zone"></a> [availability\_zone](#input\_availability\_zone) | Single-AZ instance's zone, pinned next to the NAT and the cache (D3). | `string` | `null` | no |
| <a name="input_backup_retention_days"></a> [backup\_retention\_days](#input\_backup\_retention\_days) | Automated backups (and PITR). Dev 0: rebuilt each session from Flyway; production 7 (D24). | `number` | `0` | no |
| <a name="input_db_subnet_group_name"></a> [db\_subnet\_group\_name](#input\_db\_subnet\_group\_name) | foundation's subnet group over the isolated db subnets (D3). | `string` | n/a | yes |
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Dev false: destroyed every session; production true (D24). | `bool` | `false` | no |
| <a name="input_engine_version"></a> [engine\_version](#input\_engine\_version) | Postgres version, major.minor. The major is pinned (D9); the testcontainers image in the services' tests runs the same major. | `string` | n/a | yes |
| <a name="input_final_snapshot"></a> [final\_snapshot](#input\_final\_snapshot) | Snapshot on destroy. Dev false; production true (D24). | `bool` | `false` | no |
| <a name="input_identifier"></a> [identifier](#input\_identifier) | Instance identifier, twr-<env>-postgres; foundation's IAM matches the master secret by it (D25). | `string` | n/a | yes |
| <a name="input_instance_class"></a> [instance\_class](#input\_instance\_class) | Instance class. db.t4g.micro: Graviton, the session size (D9). | `string` | `"db.t4g.micro"` | no |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | The CMK (D11): storage and the managed master secret, and the service secrets. | `string` | n/a | yes |
| <a name="input_master_username"></a> [master\_username](#input\_master\_username) | The master user: migration-only (D9, D10). | `string` | `"twr_admin"` | no |
| <a name="input_multi_az"></a> [multi\_az](#input\_multi\_az) | Standby in a second AZ. Dev false; production true (D9). | `bool` | `false` | no |
| <a name="input_security_group_id"></a> [security\_group\_id](#input\_security\_group\_id) | The db SG: in from checkout, issuance and migrate only (modules/network). | `string` | n/a | yes |
| <a name="input_service_secret_names"></a> [service\_secret\_names](#input\_service\_secret\_names) | Per-service login role => its Secrets Manager secret name. Names come from foundation, where the execution roles are scoped to them (D25). | `map(string)` | `{}` | no |
| <a name="input_service_secret_version"></a> [service\_secret\_version](#input\_service\_secret\_version) | Version of the service passwords. Bump it to rotate them: bump, migrate, redeploy (D13, D23). | `number` | `1` | no |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_address"></a> [address](#output\_address) | Endpoint host (names the instance: not for committed files). |
| <a name="output_arn"></a> [arn](#output\_arn) | Instance ARN; the managed master secret is tagged with it. |
| <a name="output_master_secret_arn"></a> [master\_secret\_arn](#output\_master\_secret\_arn) | The RDS-managed master secret, for the migration task only (D10). |
| <a name="output_port"></a> [port](#output\_port) | Endpoint port. |
| <a name="output_service_secret_arns"></a> [service\_secret\_arns](#output\_service\_secret\_arns) | Per-service login secret ARN, by role; a service takes <arn>:password:: through valueFrom (D13). |
<!-- END_TF_DOCS -->
