# observability

What outlives sessions (D16): the CMK-encrypted `ops-alarms` topic with its email subscription; the service, metrics, saga and ECS Exec log groups with retentions; the collector configuration in SSM (`templates/otel-collector-config.yaml.tftpl`); the dashboard (`templates/dashboard.json.tftpl`, from Phase 1's); six saved Logs Insights queries; and the foundation alarms: every DLQ, the age of each main queue's oldest message (3 x its visibility timeout), Lambda errors + throttles, and admission-publish failures. Every alarm pins `treat_missing_data`, notifies on recovery, and links its section of `docs/runbook-alarms.md`. Session alarms (saga, ALB, tasks) live in the session stack.

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
| [aws_cloudwatch_dashboard.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_dashboard) | resource |
| [aws_cloudwatch_log_group.audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_cloudwatch_log_group.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_cloudwatch_metric_alarm.admission_publish](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_metric_alarm) | resource |
| [aws_cloudwatch_metric_alarm.dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_metric_alarm) | resource |
| [aws_cloudwatch_metric_alarm.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_metric_alarm) | resource |
| [aws_cloudwatch_metric_alarm.queue_age](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_metric_alarm) | resource |
| [aws_cloudwatch_query_definition.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_query_definition) | resource |
| [aws_sns_topic.ops_alarms](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sns_topic) | resource |
| [aws_sns_topic_policy.ops_alarms](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sns_topic_policy) | resource |
| [aws_sns_topic_subscription.email](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sns_topic_subscription) | resource |
| [aws_ssm_parameter.collector_config](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.ops_alarms](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alert_email"></a> [alert\_email](#input\_alert\_email) | Address subscribed to ops-alarms. The subscription must be confirmed by clicking the link AWS sends (D16). | `string` | n/a | yes |
| <a name="input_audit_log_retention_days"></a> [audit\_log\_retention\_days](#input\_audit\_log\_retention\_days) | Retention of the ECS Exec audit log. | `number` | `30` | no |
| <a name="input_collector_config_parameter"></a> [collector\_config\_parameter](#input\_collector\_config\_parameter) | SSM parameter the collector sidecar reads its configuration from (the execution roles may read it). | `string` | n/a | yes |
| <a name="input_dlq_names"></a> [dlq\_names](#input\_dlq\_names) | Every DLQ name, by key (modules/messaging dlq\_names). | `map(string)` | n/a | yes |
| <a name="input_ecs_exec_log_group"></a> [ecs\_exec\_log\_group](#input\_ecs\_exec\_log\_group) | ECS Exec session audit log group (D12). | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | CMK that encrypts the ops-alarms topic (D11). | `string` | n/a | yes |
| <a name="input_lambda_function_names"></a> [lambda\_function\_names](#input\_lambda\_function\_names) | Function names by key. | `map(string)` | n/a | yes |
| <a name="input_lambda_log_groups"></a> [lambda\_log\_groups](#input\_lambda\_log\_groups) | Lambda log groups by function key (declared by modules/lambda-function), for the saved queries. | `map(string)` | n/a | yes |
| <a name="input_log_groups"></a> [log\_groups](#input\_log\_groups) | Log groups this module declares, by key: "ecs/<service>" per ECS service, "metrics" (collector EMF) and "saga". The names are decided in the root, where the IAM roles take them too. | `map(string)` | n/a | yes |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Retention of the service, metrics and saga log groups. | `number` | `3` | no |
| <a name="input_metrics_namespace"></a> [metrics\_namespace](#input\_metrics\_namespace) | CloudWatch namespace of the services' EMF metrics. | `string` | `"TicketWaitingRoom"` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Resource name prefix, twr-<env> (D25). | `string` | n/a | yes |
| <a name="input_queue_age_multiplier"></a> [queue\_age\_multiplier](#input\_queue\_age\_multiplier) | A queue is stuck when its oldest message is older than this many visibility timeouts (D16). | `number` | `3` | no |
| <a name="input_queue_names"></a> [queue\_names](#input\_queue\_names) | Main queue names by short name (modules/messaging queue\_names). | `map(string)` | n/a | yes |
| <a name="input_repository"></a> [repository](#input\_repository) | GitHub owner/name; alarm descriptions link to docs/runbook-alarms.md there. | `string` | n/a | yes |
| <a name="input_saga_state_machine_arn"></a> [saga\_state\_machine\_arn](#input\_saga\_state\_machine\_arn) | The saga's ARN (deterministic, D25), for the dashboard; the state machine itself is the session stack's. | `string` | n/a | yes |
| <a name="input_seat_table_name"></a> [seat\_table\_name](#input\_seat\_table\_name) | Seat table name, for the dashboard's conditional-check widget. | `string` | n/a | yes |
| <a name="input_visibility_timeouts"></a> [visibility\_timeouts](#input\_visibility\_timeouts) | Visibility timeout by queue short name, seconds (modules/messaging visibility\_timeouts). | `map(number)` | n/a | yes |
| <a name="input_waf_acl_name"></a> [waf\_acl\_name](#input\_waf\_acl\_name) | The session stack's web ACL name (deterministic, D25), for the dashboard. | `string` | n/a | yes |
| <a name="input_websocket_access_log_group"></a> [websocket\_access\_log\_group](#input\_websocket\_access\_log\_group) | WebSocket access log group, for the saved queries. | `string` | n/a | yes |
| <a name="input_websocket_api_id"></a> [websocket\_api\_id](#input\_websocket\_api\_id) | WebSocket API id, for the dashboard. | `string` | n/a | yes |
| <a name="input_websocket_stage_name"></a> [websocket\_stage\_name](#input\_websocket\_stage\_name) | WebSocket stage name, for the dashboard. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_alarm_names"></a> [alarm\_names](#output\_alarm\_names) | Every foundation alarm's name. |
| <a name="output_collector_config_parameter_name"></a> [collector\_config\_parameter\_name](#output\_collector\_config\_parameter\_name) | SSM parameter holding the collector configuration. |
| <a name="output_dashboard_name"></a> [dashboard\_name](#output\_dashboard\_name) | CloudWatch dashboard name. |
| <a name="output_ops_alarms_topic_arn"></a> [ops\_alarms\_topic\_arn](#output\_ops\_alarms\_topic\_arn) | ARN of the ops-alarms SNS topic. |
<!-- END_TF_DOCS -->
