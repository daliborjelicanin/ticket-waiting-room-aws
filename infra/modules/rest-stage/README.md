# rest-stage

The session half of the REST edge (D5): VPC Link v2; the `/{proxy+}` and `/events/{eventId}/queue` (`GET`, `POST`) `HTTP_PROXY` private integrations with the internal ALB as `integration_target`; a deployment that redeploys on foundation's `definition_hash`; the stage with X-Ray and access logs; and method throttling on the two buyer entry points, `POST` queue join and `POST` purchase, and nothing else (D6).

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
| [aws_api_gateway_integration.queue](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_integration) | resource |
| [aws_api_gateway_method_settings.entry](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_method_settings) | resource |
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
| <a name="input_purchase_path"></a> [purchase\_path](#input\_purchase\_path) | Resource path of purchase, /events/{eventId}/purchase, as foundation publishes it. | `string` | n/a | yes |
| <a name="input_queue_http_methods"></a> [queue\_http\_methods](#input\_queue\_http\_methods) | The methods foundation declares on the queue resource; each gets the private integration. | `list(string)` | <pre>[<br/>  "GET",<br/>  "POST"<br/>]</pre> | no |
| <a name="input_queue_path"></a> [queue\_path](#input\_queue\_path) | Resource path of queue join, /events/{eventId}/queue, as foundation publishes it. | `string` | n/a | yes |
| <a name="input_queue_resource_id"></a> [queue\_resource\_id](#input\_queue\_resource\_id) | Foundation's /events/{eventId}/queue resource (queue join and the position poll). | `string` | n/a | yes |
| <a name="input_rest_api_id"></a> [rest\_api\_id](#input\_rest\_api\_id) | Foundation's REST API (modules/rest-api). | `string` | n/a | yes |
| <a name="input_stage_name"></a> [stage\_name](#input\_stage\_name) | Stage name. Fixed by foundation: the invoke URL every QR encodes contains it (D19). | `string` | n/a | yes |
| <a name="input_subnet_ids"></a> [subnet\_ids](#input\_subnet\_ids) | Subnets for the VPC link's ENIs: the app subnets, beside the ALB (D3). | `list(string)` | n/a | yes |
| <a name="input_throttle_burst"></a> [throttle\_burst](#input\_throttle\_burst) | Burst per buyer entry point (D6). Keys: queue, purchase. | `map(number)` | <pre>{<br/>  "purchase": 200,<br/>  "queue": 1000<br/>}</pre> | no |
| <a name="input_throttle_rate"></a> [throttle\_rate](#input\_throttle\_rate) | Steady requests per second per buyer entry point, stage-wide (D6). Keys: queue, purchase. | `map(number)` | <pre>{<br/>  "purchase": 100,<br/>  "queue": 500<br/>}</pre> | no |
| <a name="input_vpc_link_name"></a> [vpc\_link\_name](#input\_vpc\_link\_name) | VPC link name, twr-<env>-edge (D25). | `string` | n/a | yes |
| <a name="input_vpc_link_security_group_id"></a> [vpc\_link\_security\_group\_id](#input\_vpc\_link\_security\_group\_id) | The VPC link's SG from modules/network: out to the ALB only. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_invoke_url"></a> [invoke\_url](#output\_invoke\_url) | The stage's invoke URL (contains the API id: print it, never commit it). |
| <a name="output_stage_arn"></a> [stage\_arn](#output\_stage\_arn) | Stage ARN, which the WAF web ACL is associated with (D6). |
| <a name="output_vpc_link_id"></a> [vpc\_link\_id](#output\_vpc\_link\_id) | VPC Link v2 id. |
<!-- END_TF_DOCS -->
