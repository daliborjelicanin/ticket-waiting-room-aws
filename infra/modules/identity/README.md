# identity

The Cognito user pool (D13, D19, D24): deletion protection, admin-created users only, the token-endpoint domain, the `ticketing` resource server (`ops`, `gate`), the public `web` client and the two client-credentials clients.


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
| [aws_cognito_resource_server.ticketing](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_resource_server) | resource |
| [aws_cognito_user_pool.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_pool) | resource |
| [aws_cognito_user_pool_client.m2m](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_pool_client) | resource |
| [aws_cognito_user_pool_client.web](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_pool_client) | resource |
| [aws_cognito_user_pool_domain.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_pool_domain) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Cognito deletion protection (D24). End of phase: false, apply, destroy. | `bool` | `true` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Name prefix, twr-<env> (D25). | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_issuer_uri"></a> [issuer\_uri](#output\_issuer\_uri) | JWT issuer, COGNITO\_ISSUER\_URI for every service. |
| <a name="output_m2m_client_ids"></a> [m2m\_client\_ids](#output\_m2m\_client\_ids) | Client id of each machine client (ops, gate). |
| <a name="output_m2m_client_secrets"></a> [m2m\_client\_secrets](#output\_m2m\_client\_secrets) | Client secret of each machine client; reaches the EventBridge connection, never a human (D13). |
| <a name="output_token_url"></a> [token\_url](#output\_token\_url) | OAuth token endpoint for the client\_credentials clients. |
| <a name="output_user_pool_arn"></a> [user\_pool\_arn](#output\_user\_pool\_arn) | User pool ARN. |
| <a name="output_user_pool_id"></a> [user\_pool\_id](#output\_user\_pool\_id) | User pool id. |
| <a name="output_web_client_id"></a> [web\_client\_id](#output\_web\_client\_id) | Public buyer client id. |
<!-- END_TF_DOCS -->