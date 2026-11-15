# iam

Every runtime role of the system (D12, retro §4): one task and one execution role per ECS service, one role per Lambda, and the roles API Gateway, EventBridge Scheduler and Step Functions use to call into ours. Inline policies from `aws_iam_policy_document`, KMS through `kms:ViaService`, every role under the permissions boundary. `main.tf` states the rule that decides which role gets a permission.


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
| [aws_iam_role.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.collector](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.ecs_exec](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.execution](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.hold_cleanup](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.issuance](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.notification](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.purchase_apigw](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.qr_generation](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.queue_notifier](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.saga](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.scheduler](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.seat_inventory](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.task](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.trust](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.waiting_room](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.websocket_apigw](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.xray](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_collector_config_parameter"></a> [collector\_config\_parameter](#input\_collector\_config\_parameter) | SSM parameter name holding the collector config, read by the execution roles. | `string` | n/a | yes |
| <a name="input_connections_table_arn"></a> [connections\_table\_arn](#input\_connections\_table\_arn) | WebSocket connection registry table ARN. | `string` | n/a | yes |
| <a name="input_db"></a> [db](#input\_db) | Names the session stack gives the database pieces, so their secrets can be granted before they exist. | <pre>object({<br/>    instance_identifier  = string<br/>    service_secret_names = map(string) # checkout, ticketing<br/>  })</pre> | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | The CMK; granted per role with kms:ViaService (D11). | `string` | n/a | yes |
| <a name="input_lambda_function_names"></a> [lambda\_function\_names](#input\_lambda\_function\_names) | Function names by key; the scheduler role may invoke hold-cleanup only. | `map(string)` | n/a | yes |
| <a name="input_log_groups"></a> [log\_groups](#input\_log\_groups) | Log group names the roles write to; the modules that create the groups take the same names. | <pre>object({<br/>    ecs      = map(string) # by ECS service key<br/>    lambda   = map(string) # by function key<br/>    metrics  = string      # collector EMF output<br/>    ecs_exec = string      # ECS Exec session audit<br/>    saga     = string      # Step Functions execution logs<br/>  })</pre> | n/a | yes |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Role name prefix, twr-<env> (D25). The boundary lets roles pass only <prefix>-* roles. | `string` | n/a | yes |
| <a name="input_permissions_boundary_arn"></a> [permissions\_boundary\_arn](#input\_permissions\_boundary\_arn) | The boundary every role carries (bootstrap, D12, D14). | `string` | n/a | yes |
| <a name="input_qr_bucket_arn"></a> [qr\_bucket\_arn](#input\_qr\_bucket\_arn) | QR bucket ARN. | `string` | n/a | yes |
| <a name="input_queue_arns"></a> [queue\_arns](#input\_queue\_arns) | Main queue ARNs by short name (modules/messaging). | `map(string)` | n/a | yes |
| <a name="input_repository_arns"></a> [repository\_arns](#input\_repository\_arns) | ECR repository ARNs by Gradle module name. | `map(string)` | n/a | yes |
| <a name="input_saga_state_machine_name"></a> [saga\_state\_machine\_name](#input\_saga\_state\_machine\_name) | The checkout saga's state machine name (session stack). | `string` | n/a | yes |
| <a name="input_seat_table_arn"></a> [seat\_table\_arn](#input\_seat\_table\_arn) | Seat inventory table ARN. | `string` | n/a | yes |
| <a name="input_ses_from_address"></a> [ses\_from\_address](#input\_ses\_from\_address) | The one address notification-service may send as. | `string` | n/a | yes |
| <a name="input_topic_arns"></a> [topic\_arns](#input\_topic\_arns) | Topic ARNs by short name (modules/messaging). | `map(string)` | n/a | yes |
| <a name="input_websocket_api_execution_arn"></a> [websocket\_api\_execution\_arn](#input\_websocket\_api\_execution\_arn) | Execution ARN of the WebSocket API, for the notifier's ManageConnections. null until that API exists. | `string` | `null` | no |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_role_arns"></a> [role\_arns](#output\_role\_arns) | Role ARN by key: <service>-task, <service>-execution, <function>-lambda, websocket-apigw, purchase-apigw, hold-cleanup-scheduler, saga. |
<!-- END_TF_DOCS -->