# websocket-api

The queue-position socket (runbook-websocket-queue.md): a WEBSOCKET API whose `$connect` and `$disconnect` are API Gateway service integrations onto the connection table (no Lambda), each with the three response pieces a non-proxy route needs (`/200/` integration response, its template, a route response), a `dev` stage with auto-deploy, `$connect` throttling (D18) and access logs that leave out the query string. The VTL lives in `templates/`.

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
| [aws_apigatewayv2_api.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_api) | resource |
| [aws_apigatewayv2_integration.route](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_integration) | resource |
| [aws_apigatewayv2_integration_response.route](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_integration_response) | resource |
| [aws_apigatewayv2_route.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_route) | resource |
| [aws_apigatewayv2_route_response.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_route_response) | resource |
| [aws_apigatewayv2_stage.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_stage) | resource |
| [aws_cloudwatch_log_group.access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_access_log_group_name"></a> [access\_log\_group\_name](#input\_access\_log\_group\_name) | Access log group, declared here with its retention. | `string` | n/a | yes |
| <a name="input_account_logging_role_arn"></a> [account\_logging\_role\_arn](#input\_account\_logging\_role\_arn) | API Gateway's account-level CloudWatch role, as the account setting reports it. Access logs are refused until it is set; passing it orders the stage after the setting. | `string` | n/a | yes |
| <a name="input_connect_throttle"></a> [connect\_throttle](#input\_connect\_throttle) | Rate (requests/s) and burst for $connect, and the default for every route (D18). | <pre>object({<br/>    rate_limit  = number<br/>    burst_limit = number<br/>  })</pre> | <pre>{<br/>  "burst_limit": 200,<br/>  "rate_limit": 100<br/>}</pre> | no |
| <a name="input_connections_table_name"></a> [connections\_table\_name](#input\_connections\_table\_name) | The connection registry the routes write (ConnectionTableSchema). | `string` | n/a | yes |
| <a name="input_integration_role_arn"></a> [integration\_role\_arn](#input\_integration\_role\_arn) | Role API Gateway assumes for the DynamoDB writes (modules/iam websocket-apigw). | `string` | n/a | yes |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Access log retention. | `number` | `3` | no |
| <a name="input_name"></a> [name](#input\_name) | API name, twr-<env>-queue-socket (D25). | `string` | n/a | yes |
| <a name="input_stage_name"></a> [stage\_name](#input\_stage\_name) | Stage name; part of both the client URL and the notifier's endpoint. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_access_log_group_name"></a> [access\_log\_group\_name](#output\_access\_log\_group\_name) | Access log group. |
| <a name="output_api_id"></a> [api\_id](#output\_api\_id) | WebSocket API id. |
| <a name="output_client_url"></a> [client\_url](#output\_client\_url) | What browsers open: wss://<api-id>.execute-api.<region>.amazonaws.com/<stage>. |
| <a name="output_execution_arn"></a> [execution\_arn](#output\_execution\_arn) | Execution ARN, for the notifier's execute-api:ManageConnections. |
| <a name="output_management_endpoint"></a> [management\_endpoint](#output\_management\_endpoint) | The notifier's WEBSOCKET\_ENDPOINT: the same host over https. Not the SDK default, which is the control plane. |
<!-- END_TF_DOCS -->
