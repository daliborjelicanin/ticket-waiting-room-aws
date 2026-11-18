# waf

The edge web ACL on the REST stage (D6), logging to foundation's `aws-waf-logs-*` bucket with `Authorization` redacted. A `buyer-entry` rule labels `POST` queue join and purchase (regex built from foundation's resource paths); the flood guard (per IP), credential guard (per `Authorization`), anonymous-IP list and Bot Control (common) are scoped down to that label, so none can reach a path the saga calls. The AWS baseline (IP reputation, known bad inputs, common rule set) covers the whole stage in `count` mode until reviewed. An optional allow rule for the load-test runner's address comes first. No JA4 fleet guard: WAF computes no JA4 on a REST stage (D6 spike, 2026-11-17).

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
| [aws_wafv2_ip_set.loadtest](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_ip_set) | resource |
| [aws_wafv2_web_acl.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_web_acl) | resource |
| [aws_wafv2_web_acl_association.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_web_acl_association) | resource |
| [aws_wafv2_web_acl_logging_configuration.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/wafv2_web_acl_logging_configuration) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_baseline_mode"></a> [baseline\_mode](#input\_baseline\_mode) | AWS managed baseline (IP reputation, known bad inputs, common rule set): count until its sampled requests, the saga's included, have been reviewed; then block (D5, D6). | `string` | `"count"` | no |
| <a name="input_buyer_entry_paths"></a> [buyer\_entry\_paths](#input\_buyer\_entry\_paths) | Resource paths of the buyer entry points, as foundation publishes them (/events/{eventId}/queue, /events/{eventId}/purchase). Only POST to these gets the buyer rules (D6). | `list(string)` | n/a | yes |
| <a name="input_credential_guard_limit"></a> [credential\_guard\_limit](#input\_credential\_guard\_limit) | Buyer-entry requests per Authorization header per 60 s: faster than a human joins or buys (D6). | `number` | `30` | no |
| <a name="input_flood_guard_limit"></a> [flood\_guard\_limit](#input\_flood\_guard\_limit) | Buyer-entry requests per IP per 60 s. Generous: CGNAT and venue Wi-Fi share one address (D6). | `number` | `300` | no |
| <a name="input_loadtest_allow_cidr"></a> [loadtest\_allow\_cidr](#input\_loadtest\_allow\_cidr) | The load test runner's address (a.b.c.d/32), allowed before the buyer rules for the concurrency proof only. Passed at apply time, never committed; null outside that run (D6). | `string` | `null` | no |
| <a name="input_log_bucket_arn"></a> [log\_bucket\_arn](#input\_log\_bucket\_arn) | Foundation's WAF log bucket. WAF only delivers to a bucket named aws-waf-logs-*. | `string` | n/a | yes |
| <a name="input_name"></a> [name](#input\_name) | Web ACL name, twr-<env>-edge: foundation's dashboard reads its metrics by this name (D25). | `string` | n/a | yes |
| <a name="input_resource_arn"></a> [resource\_arn](#input\_resource\_arn) | The REST API stage the ACL protects. | `string` | n/a | yes |
| <a name="input_stage_name"></a> [stage\_name](#input\_stage\_name) | The stage the ACL is on: WAF sees it as the first segment of the URI. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_arn"></a> [arn](#output\_arn) | Web ACL ARN. |
| <a name="output_name"></a> [name](#output\_name) | Web ACL name (the WebACL dimension of the AWS/WAFV2 metrics). |
<!-- END_TF_DOCS -->
