# ecr

One ECR repository per deployable (D24): immutable tags, basic scan on push, AES256, lifecycle keeping the last N images and expiring untagged ones after a day.

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
| [aws_ecr_lifecycle_policy.mirror](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_lifecycle_policy) | resource |
| [aws_ecr_lifecycle_policy.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_lifecycle_policy) | resource |
| [aws_ecr_pull_through_cache_rule.public](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_pull_through_cache_rule) | resource |
| [aws_ecr_repository.mirror](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_repository) | resource |
| [aws_ecr_repository.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecr_repository) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_force_delete"></a> [force\_delete](#input\_force\_delete) | Let destroy delete repositories that still hold images (rebuilt by CI anyway). | `bool` | `false` | no |
| <a name="input_keep_images"></a> [keep\_images](#input\_keep\_images) | Images kept per repository (D24). | `number` | `3` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Repository name prefix, twr-<env> (D25). ci-images may push only to <prefix>-* (D14). | `string` | n/a | yes |
| <a name="input_public_mirrors"></a> [public\_mirrors](#input\_public\_mirrors) | public.ecr.aws repositories pulled through this registry's cache (e.g. aws-observability/aws-otel-collector), so their layers skip the NAT. | `list(string)` | `[]` | no |
| <a name="input_repositories"></a> [repositories](#input\_repositories) | Deployables, named as their Gradle modules. | `list(string)` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_mirror_arns"></a> [mirror\_arns](#output\_mirror\_arns) | Cached repository ARN per public.ecr.aws path. |
| <a name="output_mirror_urls"></a> [mirror\_urls](#output\_mirror\_urls) | Cached repository URL per public.ecr.aws path (contains the account id: not for committed files). |
| <a name="output_repository_arns"></a> [repository\_arns](#output\_repository\_arns) | Repository ARN per deployable. |
| <a name="output_repository_urls"></a> [repository\_urls](#output\_repository\_urls) | Repository URL per deployable (contains the account id: not for committed files). |
<!-- END_TF_DOCS -->