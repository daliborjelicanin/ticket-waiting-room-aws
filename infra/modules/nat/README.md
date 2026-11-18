# nat

Session egress for the app tier (D4): `zonal` (default, sessions) puts one gateway with an EIP in the first public subnet (AZ a) and points every app route table at it; `regional` is the production shape, one gateway attached to the VPC with AWS-managed addresses, one variable away. The data tier gets no route. ECR layers and DynamoDB take foundation's gateway endpoints, not this.

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
| [aws_eip.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/eip) | resource |
| [aws_nat_gateway.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/nat_gateway) | resource |
| [aws_route.app_egress](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/route) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_app_route_table_ids"></a> [app\_route\_table\_ids](#input\_app\_route\_table\_ids) | The app tier's route tables; each gets 0.0.0.0/0 to the gateway. The data tier gets none (D3). | `list(string)` | n/a | yes |
| <a name="input_mode"></a> [mode](#input\_mode) | zonal: one gateway in AZ a, for sessions. regional: the production shape, one switch away (D4). | `string` | `"zonal"` | no |
| <a name="input_name"></a> [name](#input\_name) | Name tag of the gateway and its address, twr-<env>-nat (D25). | `string` | n/a | yes |
| <a name="input_public_subnet_ids"></a> [public\_subnet\_ids](#input\_public\_subnet\_ids) | Public subnets in AZ order; zonal mode uses the first (AZ a). | `list(string)` | n/a | yes |
| <a name="input_vpc_id"></a> [vpc\_id](#input\_vpc\_id) | The VPC, for regional mode. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_id"></a> [id](#output\_id) | NAT gateway id. |
<!-- END_TF_DOCS -->
