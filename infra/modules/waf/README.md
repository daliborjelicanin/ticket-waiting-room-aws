# waf

The edge web ACL on the REST stage (D6), with logging to foundation's `aws-waf-logs-*` bucket and the `Authorization` header redacted. So far the D6 spike's shape: the JA4-keyed fleet guard in count mode. The other buyer rules and the managed baseline follow in slice 2.

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
| [aws_wafv2_web_acl.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_web_acl) | resource |
| [aws_wafv2_web_acl_association.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_web_acl_association) | resource |
| [aws_wafv2_web_acl_logging_configuration.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_web_acl_logging_configuration) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_fleet_guard_limit"></a> [fleet\_guard\_limit](#input\_fleet\_guard\_limit) | Requests per JA4 fingerprint per 60 s before the fleet guard matches (D6). | `number` | `300` | no |
| <a name="input_log_bucket_arn"></a> [log\_bucket\_arn](#input\_log\_bucket\_arn) | Foundation's WAF log bucket. WAF only delivers to a bucket named aws-waf-logs-*. | `string` | n/a | yes |
| <a name="input_name"></a> [name](#input\_name) | Web ACL name, twr-<env>-edge: foundation's dashboard reads its metrics by this name (D25). | `string` | n/a | yes |
| <a name="input_resource_arn"></a> [resource\_arn](#input\_resource\_arn) | The REST API stage the ACL protects. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_arn"></a> [arn](#output\_arn) | Web ACL ARN. |
| <a name="output_name"></a> [name](#output\_name) | Web ACL name (the WebACL dimension of the AWS/WAFV2 metrics). |
<!-- END_TF_DOCS -->
