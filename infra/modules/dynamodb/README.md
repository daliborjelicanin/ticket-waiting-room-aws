# dynamodb

One on-demand DynamoDB table with the Phase 1 silent defaults pinned (retro §3; D11, D24 in `docs/phase2-plan.md`): TTL on, PITR, deletion protection, AWS-owned encryption. The schema is passed by the root, beside the Java contract it mirrors.

<!-- BEGIN_TF_DOCS -->
## Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | ~> 1.17 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.73 |

## Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | ~> 6.73 |

## Modules

No modules.

## Resources

| Name | Type |
|------|------|
| [aws_dynamodb_table.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/dynamodb_table) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | AWS-side deletion protection (D24). Set false, apply, then destroy at the end of the phase. | `bool` | `true` | no |
| <a name="input_global_secondary_indexes"></a> [global\_secondary\_indexes](#input\_global\_secondary\_indexes) | GSIs. Key attributes are type S; keep the projection minimal, every GSI doubles a write. | <pre>list(object({<br/>    name            = string<br/>    hash_key        = string<br/>    range_key       = optional(string)<br/>    projection_type = optional(string, "KEYS_ONLY")<br/>  }))</pre> | `[]` | no |
| <a name="input_hash_key"></a> [hash\_key](#input\_hash\_key) | Partition key attribute (type S). | `string` | n/a | yes |
| <a name="input_name"></a> [name](#input\_name) | Table name, twr-<env>-<name> (D25). | `string` | n/a | yes |
| <a name="input_pitr_recovery_days"></a> [pitr\_recovery\_days](#input\_pitr\_recovery\_days) | PITR window in days (D24). | `number` | `7` | no |
| <a name="input_point_in_time_recovery"></a> [point\_in\_time\_recovery](#input\_point\_in\_time\_recovery) | Continuous backups (D24). | `bool` | `true` | no |
| <a name="input_range_key"></a> [range\_key](#input\_range\_key) | Sort key attribute (type S), or null. | `string` | `null` | no |
| <a name="input_ttl_attribute"></a> [ttl\_attribute](#input\_ttl\_attribute) | TTL attribute (epoch seconds, Number). Required: both project tables rely on TTL (retro §3). | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_arn"></a> [arn](#output\_arn) | Table ARN. IAM policies also need "<arn>/index/*" for GSI queries (retro §3). |
| <a name="output_name"></a> [name](#output\_name) | Table name. |
<!-- END_TF_DOCS -->