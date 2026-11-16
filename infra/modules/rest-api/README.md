# rest-api

The public edge (D5): a REGIONAL REST API with a Cognito authorizer, `POST /events/{eventId}/purchase` as a Step Functions `StartExecution` service integration (202 / 409 on a retried order id / 400 / 502, Step Functions' messages never passed through), and the `ANY /{proxy+}` method. The proxy's VPC_LINK integration, the deployment and the stage belong to the session stack, which redeploys on `definition_hash`. The invoke URL is computed from the fixed stage name, so it exists before the stage.

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
| [aws_api_gateway_authorizer.users](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_authorizer) | resource |
| [aws_api_gateway_integration.purchase](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_integration) | resource |
| [aws_api_gateway_integration_response.purchase](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_integration_response) | resource |
| [aws_api_gateway_method.proxy](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_method) | resource |
| [aws_api_gateway_method.purchase](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_method) | resource |
| [aws_api_gateway_method_response.purchase](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_method_response) | resource |
| [aws_api_gateway_resource.event](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_resource) | resource |
| [aws_api_gateway_resource.events](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_resource) | resource |
| [aws_api_gateway_resource.proxy](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_resource) | resource |
| [aws_api_gateway_resource.purchase](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_resource) | resource |
| [aws_api_gateway_rest_api.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/api_gateway_rest_api) | resource |
| [aws_cloudwatch_log_group.access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_access_log_group_name"></a> [access\_log\_group\_name](#input\_access\_log\_group\_name) | Access log group for the session stack's stage, declared here with its retention. | `string` | n/a | yes |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Access log retention. | `number` | `3` | no |
| <a name="input_name"></a> [name](#input\_name) | API name, twr-<env>-edge (D25). | `string` | n/a | yes |
| <a name="input_saga_state_machine_arn"></a> [saga\_state\_machine\_arn](#input\_saga\_state\_machine\_arn) | The checkout saga's ARN. Deterministic (D25), so it is known before the session stack creates it. | `string` | n/a | yes |
| <a name="input_stage_name"></a> [stage\_name](#input\_stage\_name) | The stage the session stack creates. Fixed, because the invoke URL (and every QR) contains it. | `string` | n/a | yes |
| <a name="input_start_execution_role_arn"></a> [start\_execution\_role\_arn](#input\_start\_execution\_role\_arn) | Role API Gateway assumes to start the saga (modules/iam purchase-apigw). | `string` | n/a | yes |
| <a name="input_user_pool_arn"></a> [user\_pool\_arn](#input\_user\_pool\_arn) | Cognito user pool behind the purchase authorizer. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_access_log_group_arn"></a> [access\_log\_group\_arn](#output\_access\_log\_group\_arn) | Access log group ARN for the session stage. |
| <a name="output_definition_hash"></a> [definition\_hash](#output\_definition\_hash) | Hash of everything here a deployment snapshots; part of the session deployment's triggers. |
| <a name="output_execution_arn"></a> [execution\_arn](#output\_execution\_arn) | Execution ARN (for WAF and permissions in the session stack). |
| <a name="output_invoke_url"></a> [invoke\_url](#output\_invoke\_url) | https://<rest-api-id>.execute-api.<region>.amazonaws.com/<stage>: TICKET\_VERIFY\_BASE\_URL and the saga's services base URL. |
| <a name="output_proxy_http_method"></a> [proxy\_http\_method](#output\_proxy\_http\_method) | The /{proxy+} method (ANY). |
| <a name="output_proxy_resource_id"></a> [proxy\_resource\_id](#output\_proxy\_resource\_id) | The /{proxy+} resource, which the session stack's VPC\_LINK integration attaches to. |
| <a name="output_rest_api_id"></a> [rest\_api\_id](#output\_rest\_api\_id) | REST API id. |
| <a name="output_root_resource_id"></a> [root\_resource\_id](#output\_root\_resource\_id) | Root resource id. |
<!-- END_TF_DOCS -->
