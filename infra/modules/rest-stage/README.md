# rest-stage

The session half of the REST edge (D5): VPC Link v2, the `/{proxy+}` `HTTP_PROXY` private integration with the internal ALB as `integration_target`, a deployment that redeploys on foundation's `definition_hash`, and the stage with X-Ray and access logs. The API, its resources and methods are foundation's (`modules/rest-api`).

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
| [aws_api_gateway_deployment.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_deployment) | resource |
| [aws_api_gateway_integration.proxy](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_integration) | resource |
| [aws_api_gateway_stage.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_stage) | resource |
| [aws_apigatewayv2_vpc_link.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_vpc_link) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_access_log_group_arn"></a> [access\_log\_group\_arn](#input\_access\_log\_group\_arn) | Access log group, declared in foundation with its retention. | `string` | n/a | yes |
| <a name="input_alb_arn"></a> [alb\_arn](#input\_alb\_arn) | The internal ALB, the integration target (D5). | `string` | n/a | yes |
| <a name="input_alb_dns_name"></a> [alb\_dns\_name](#input\_alb\_dns\_name) | The internal ALB's DNS name, the host in the integration URI. | `string` | n/a | yes |
| <a name="input_definition_hash"></a> [definition\_hash](#input\_definition\_hash) | Foundation's published hash of everything a deployment snapshots; a change redeploys the stage. | `string` | n/a | yes |
| <a name="input_proxy_http_method"></a> [proxy\_http\_method](#input\_proxy\_http\_method) | Foundation's /{proxy+} method (ANY). | `string` | n/a | yes |
| <a name="input_proxy_resource_id"></a> [proxy\_resource\_id](#input\_proxy\_resource\_id) | Foundation's /{proxy+} resource. | `string` | n/a | yes |
| <a name="input_rest_api_id"></a> [rest\_api\_id](#input\_rest\_api\_id) | Foundation's REST API (modules/rest-api). | `string` | n/a | yes |
| <a name="input_stage_name"></a> [stage\_name](#input\_stage\_name) | Stage name. Fixed by foundation: the invoke URL every QR encodes contains it (D19). | `string` | n/a | yes |
| <a name="input_subnet_ids"></a> [subnet\_ids](#input\_subnet\_ids) | Subnets for the VPC link's ENIs: the app subnets, beside the ALB (D3). | `list(string)` | n/a | yes |
| <a name="input_vpc_link_name"></a> [vpc\_link\_name](#input\_vpc\_link\_name) | VPC link name, twr-<env>-edge (D25). | `string` | n/a | yes |
| <a name="input_vpc_link_security_group_id"></a> [vpc\_link\_security\_group\_id](#input\_vpc\_link\_security\_group\_id) | The VPC link's SG from modules/network: out to the ALB only. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_invoke_url"></a> [invoke\_url](#output\_invoke\_url) | The stage's invoke URL (contains the API id: print it, never commit it). |
| <a name="output_stage_arn"></a> [stage\_arn](#output\_stage\_arn) | Stage ARN, which the WAF web ACL is associated with (D6). |
| <a name="output_vpc_link_id"></a> [vpc\_link\_id](#output\_vpc\_link\_id) | VPC Link v2 id. |
<!-- END_TF_DOCS -->
