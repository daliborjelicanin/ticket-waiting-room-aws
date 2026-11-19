# saga

The checkout saga's session half (D5, D11, D13).

**The state machine** `<prefix>-checkout-saga` is STANDARD and runs as foundation's saga role. It is built from `docs/checkout-saga.asl.json`, the same file the checkout and ticket-issuance contract tests read. Its three placeholders (`__CONNECTION_ARN__`, `__QUEUE_URL__`, `__TOPIC_ARN__`) are replaced rather than the file copied into a template, so the deployed saga and the tested one cannot drift. A precondition refuses a placeholder that survives. Execution input and history carry the buyer's email, so they are on the CMK (D11). Every transition is logged to foundation's group without execution data. X-Ray is on.

**The EventBridge connection** is what its HTTP tasks call the services with. It uses OAuth client_credentials against the Cognito token endpoint, with the ops client and the `ticketing/ops` scope only. It is named `<prefix>-saga`, the only name the saga role may retrieve credentials from (`modules/iam`). EventBridge fetches, caches and refreshes the token. The authorization lives in an `events!connection/…` secret on the AWS-owned key: the same client secret is already in state, so D11 records why a CMK would gate nothing.

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
| [aws_cloudwatch_event_connection.saga](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_event_connection) | resource |
| [aws_sfn_state_machine.saga](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sfn_state_machine) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_client_id"></a> [client\_id](#input\_client\_id) | The ops app client (foundation's cognito/ops\_client\_id): confidential, client\_credentials only. | `string` | n/a | yes |
| <a name="input_client_secret"></a> [client\_secret](#input\_client\_secret) | The ops client's secret, read in the session root from the client itself. The one secret D13 accepts in state; never typed, never printed. | `string` | n/a | yes |
| <a name="input_definition"></a> [definition](#input\_definition) | The saga's Amazon States Language, as written in docs/checkout-saga.asl.json: JSON with the __CONNECTION\_ARN\_\_, \_\_QUEUE\_URL\_\_ and \_\_TOPIC\_ARN__ placeholders that the contract tests read the same file around. | `string` | n/a | yes |
| <a name="input_issuance_queue_url"></a> [issuance\_queue\_url](#input\_issuance\_queue\_url) | The ticket-issuance queue the saga enqueues to after a confirmed purchase. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | The CMK (D11): execution input and history carry the buyer's email. | `string` | n/a | yes |
| <a name="input_log_group_arn"></a> [log\_group\_arn](#input\_log\_group\_arn) | Foundation's /aws/vendedlogs/states/<prefix>-checkout-saga group (D25). | `string` | n/a | yes |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | twr-<env>. The connection is named <prefix>-saga: the saga role may retrieve credentials only from connections under this prefix (modules/iam integrations.tf). | `string` | n/a | yes |
| <a name="input_purchase_confirmed_topic_arn"></a> [purchase\_confirmed\_topic\_arn](#input\_purchase\_confirmed\_topic\_arn) | The purchase-confirmed topic the saga notifies on (the buyer's email goes to notification-service through it). | `string` | n/a | yes |
| <a name="input_role_arn"></a> [role\_arn](#input\_role\_arn) | Foundation's saga role (modules/iam): the HTTP tasks, the connection, SQS, SNS, X-Ray, log delivery, and the key for its own execution data. | `string` | n/a | yes |
| <a name="input_scope"></a> [scope](#input\_scope) | The one scope the saga asks for. The services' operator paths require it (security-starter EndpointAuthorization). | `string` | `"ticketing/ops"` | no |
| <a name="input_token_url"></a> [token\_url](#input\_token\_url) | The Cognito domain's OAuth token endpoint (foundation's cognito/token\_url). | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_connection_arn"></a> [connection\_arn](#output\_connection\_arn) | The connection the saga's HTTP tasks name (ConnectionArn in the ASL). |
| <a name="output_connection_name"></a> [connection\_name](#output\_connection\_name) | The connection's name, <prefix>-saga. |
| <a name="output_connection_secret_arn"></a> [connection\_secret\_arn](#output\_connection\_secret\_arn) | The events!connection/... secret EventBridge keeps the authorization in (AWS-owned key, D11). |
| <a name="output_state_machine_arn"></a> [state\_machine\_arn](#output\_state\_machine\_arn) | The checkout saga, as foundation's purchase integration starts it. |
<!-- END_TF_DOCS -->
