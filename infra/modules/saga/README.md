# saga

The checkout saga's session half (D5, D13). For now it holds the EventBridge connection that the Step Functions HTTP tasks call the services with. The state machine joins it next. The connection uses OAuth client credentials against the Cognito token endpoint with the ops client and the `ticketing/ops` scope only. It is named `<prefix>-saga`, the only name the saga role may retrieve credentials from (`modules/iam`). EventBridge fetches, caches and refreshes the token. Its authorization lives in an `events!connection/…` secret on the AWS-owned key: the same client secret is already in state, so D11 records why a CMK would gate nothing.

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

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_client_id"></a> [client\_id](#input\_client\_id) | The ops app client (foundation's cognito/ops\_client\_id): confidential, client\_credentials only. | `string` | n/a | yes |
| <a name="input_client_secret"></a> [client\_secret](#input\_client\_secret) | The ops client's secret, read in the session root from the client itself. The one secret D13 accepts in state; never typed, never printed. | `string` | n/a | yes |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | twr-<env>. The connection is named <prefix>-saga: the saga role may retrieve credentials only from connections under this prefix (modules/iam integrations.tf). | `string` | n/a | yes |
| <a name="input_scope"></a> [scope](#input\_scope) | The one scope the saga asks for. The services' operator paths require it (security-starter EndpointAuthorization). | `string` | `"ticketing/ops"` | no |
| <a name="input_token_url"></a> [token\_url](#input\_token\_url) | The Cognito domain's OAuth token endpoint (foundation's cognito/token\_url). | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_connection_arn"></a> [connection\_arn](#output\_connection\_arn) | The connection the saga's HTTP tasks name (ConnectionArn in the ASL). |
| <a name="output_connection_name"></a> [connection\_name](#output\_connection\_name) | The connection's name, <prefix>-saga. |
| <a name="output_connection_secret_arn"></a> [connection\_secret\_arn](#output\_connection\_secret\_arn) | The events!connection/... secret EventBridge keeps the authorization in (AWS-owned key, D11). |
<!-- END_TF_DOCS -->
