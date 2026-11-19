# cache

The waiting room's queue store (D8): Valkey on one `cache.t4g.micro` node, TLS in transit required, at rest on the CMK (the queue holds bearer tokens, D11), reachable only from the waiting room's SG (D3), pinned to the NAT's AZ, no snapshots. No AUTH user: TLS plus SG isolation is Phase 2's line; RBAC or IAM authentication is the documented production step.

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
| [aws_elasticache_replication_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/elasticache_replication_group) | resource |
| [aws_elasticache_subnet_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/elasticache_subnet_group) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_availability_zone"></a> [availability\_zone](#input\_availability\_zone) | The one node's zone, pinned next to the NAT (D3). | `string` | n/a | yes |
| <a name="input_engine_version"></a> [engine\_version](#input\_engine\_version) | Valkey version, major.minor. The parameter group family follows the major. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | The CMK, for encryption at rest (D11). | `string` | n/a | yes |
| <a name="input_name"></a> [name](#input\_name) | Replication group id and subnet group name, twr-<env>-queue (D25). | `string` | n/a | yes |
| <a name="input_node_type"></a> [node\_type](#input\_node\_type) | Node type. cache.t4g.micro: Graviton, the session size (D8). | `string` | `"cache.t4g.micro"` | no |
| <a name="input_security_group_id"></a> [security\_group\_id](#input\_security\_group\_id) | The cache SG: in from waiting-room only (modules/network). | `string` | n/a | yes |
| <a name="input_subnet_ids"></a> [subnet\_ids](#input\_subnet\_ids) | The isolated db subnets (D3). | `list(string)` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_port"></a> [port](#output\_port) | Port. |
| <a name="output_primary_endpoint"></a> [primary\_endpoint](#output\_primary\_endpoint) | Primary endpoint host (names the cluster: not for committed files). |
<!-- END_TF_DOCS -->
