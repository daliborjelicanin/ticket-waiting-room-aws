# lambda-function

One container-image Lambda (D7, D22, retro §3): arm64, image pinned by digest, its own log group with a retention, active tracing, and an optional SQS event source mapping with `ReportBatchItemFailures` and `enabled = true`. The role comes from `modules/iam`.


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
| [aws_cloudwatch_log_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_lambda_event_source_mapping.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_event_source_mapping) | resource |
| [aws_lambda_function.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Environment variables: names and references only, never a typed value (D25). | `map(string)` | `{}` | no |
| <a name="input_function_name"></a> [function\_name](#input\_function\_name) | Function name, twr-<env>-<name> (D25). | `string` | n/a | yes |
| <a name="input_image_uri"></a> [image\_uri](#input\_image\_uri) | <repository-url>@sha256:<digest>. By digest, never by tag (D15). | `string` | n/a | yes |
| <a name="input_log_group_name"></a> [log\_group\_name](#input\_log\_group\_name) | Log group the function writes to; its role may write only there (modules/iam). | `string` | n/a | yes |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Log retention (Phase 1: 3 days). | `number` | `3` | no |
| <a name="input_memory_size"></a> [memory\_size](#input\_memory\_size) | MB; Lambda scales CPU with it. | `number` | `1024` | no |
| <a name="input_role_arn"></a> [role\_arn](#input\_role\_arn) | Execution role, from modules/iam (D12). | `string` | n/a | yes |
| <a name="input_sqs_event_source"></a> [sqs\_event\_source](#input\_sqs\_event\_source) | Queue this function consumes, or null. Batch 10 and no batching window, as in Phase 1. | <pre>object({<br/>    queue_arn               = string<br/>    batch_size              = optional(number, 10)<br/>    batching_window_seconds = optional(number, 0)<br/>  })</pre> | `null` | no |
| <a name="input_timeout"></a> [timeout](#input\_timeout) | Seconds. A queue consumer's visibility timeout is derived from it (modules/messaging). | `number` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_function_arn"></a> [function\_arn](#output\_function\_arn) | Function ARN. |
| <a name="output_function_name"></a> [function\_name](#output\_function\_name) | Function name. |
| <a name="output_log_group_name"></a> [log\_group\_name](#output\_log\_group\_name) | The function's log group. |
<!-- END_TF_DOCS -->