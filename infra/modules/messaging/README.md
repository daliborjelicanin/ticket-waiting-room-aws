# messaging

SQS queues, each with a DLQ and `redrive_allow_policy`, and SNS topics with raw-delivery queue subscriptions (D11, retro §3 in `docs/`). Visibility timeouts are derived from the consumer, never typed; everything is on the CMK with 5-minute data-key reuse; every queue policy denies non-TLS and lets only its own topic deliver.


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
| [aws_sns_topic.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sns_topic) | resource |
| [aws_sns_topic_subscription.queue](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sns_topic_subscription) | resource |
| [aws_sqs_queue.dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.main](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue_policy.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue_policy) | resource |
| [aws_sqs_queue_redrive_allow_policy.dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue_redrive_allow_policy) | resource |
| [aws_iam_policy_document.queue](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_dlq_retention_seconds"></a> [dlq\_retention\_seconds](#input\_dlq\_retention\_seconds) | DLQs: 14 days, the maximum. A DLQ is read by a human, days later. | `number` | `1209600` | no |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | The CMK: purchase-confirmed carries the buyer's email (D11). Queues and topics use it. | `string` | n/a | yes |
| <a name="input_message_retention_seconds"></a> [message\_retention\_seconds](#input\_message\_retention\_seconds) | Main queues: 4 days, the SQS default, pinned. | `number` | `345600` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Name prefix, twr-<env> (D25). | `string` | n/a | yes |
| <a name="input_queues"></a> [queues](#input\_queues) | Queues by short name; each gets a DLQ. The visibility timeout is derived from the consumer,<br/>never typed (retro §3):<br/>  lambda: 6 x the function timeout (AWS's rule for event source mappings)<br/>  pump:   batch\_size x message\_seconds x 3 (SqsMessagePump walks a batch on one thread,<br/>          and the visibility clock starts at receive for the whole batch) | <pre>map(object({<br/>    consumer          = string           # lambda | pump<br/>    function_timeout  = optional(number) # lambda: seconds<br/>    batch_size        = optional(number) # pump: messages per receive<br/>    message_seconds   = optional(number) # pump: worst-case seconds per message<br/>    max_receive_count = number<br/>  }))</pre> | n/a | yes |
| <a name="input_topics"></a> [topics](#input\_topics) | SNS topics by short name, with the queues (keys of var.queues) subscribed to each. | <pre>map(object({<br/>    queue_subscribers = list(string)<br/>  }))</pre> | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_dlq_arns"></a> [dlq\_arns](#output\_dlq\_arns) | DLQ ARN by short name of its main queue (the DLQ-depth alarms, D16). |
| <a name="output_queue_arns"></a> [queue\_arns](#output\_queue\_arns) | Main queue ARN by short name. |
| <a name="output_queue_urls"></a> [queue\_urls](#output\_queue\_urls) | Main queue URL by short name (contains the account id: not for committed files). |
| <a name="output_topic_arns"></a> [topic\_arns](#output\_topic\_arns) | Topic ARN by short name. |
| <a name="output_visibility_timeouts"></a> [visibility\_timeouts](#output\_visibility\_timeouts) | Derived visibility timeout by queue, in seconds (the oldest-message alarms, D16). |
<!-- END_TF_DOCS -->