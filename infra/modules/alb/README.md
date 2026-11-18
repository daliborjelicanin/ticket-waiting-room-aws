# alb

The internal Application Load Balancer behind the REST API's VPC link (D5): internal only, invalid header fields dropped, an HTTP listener whose default action is a fixed `404 {"error":"NO_ROUTE"}`. Per running service, a target group (IP targets, readiness health check, 30 s deregistration delay) and a path rule; anything unrouted gets the 404. They stay here, not in ecs-service, so Phase 3 registers pods into the same groups (D21).

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
| [aws_lb.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb) | resource |
| [aws_lb_listener.http](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb_listener) | resource |
| [aws_lb_listener_rule.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb_listener_rule) | resource |
| [aws_lb_target_group.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb_target_group) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_idle_timeout_seconds"></a> [idle\_timeout\_seconds](#input\_idle\_timeout\_seconds) | Connection idle timeout. Above API Gateway's 29 s integration timeout, so the edge always times out first. | `number` | `60` | no |
| <a name="input_name"></a> [name](#input\_name) | Load balancer name, twr-<env>-alb (D25). | `string` | n/a | yes |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | twr-<env>; target groups are named <prefix>-<service>-tg (D25). | `string` | n/a | yes |
| <a name="input_routes"></a> [routes](#input\_routes) | Per service: listener rule priority and path patterns (ALB globs, * crosses /, at most 5). Only running services get a route; anything else gets the default NO\_ROUTE 404. | <pre>map(object({<br/>    priority = number<br/>    paths    = list(string)<br/>  }))</pre> | `{}` | no |
| <a name="input_security_group_id"></a> [security\_group\_id](#input\_security\_group\_id) | The ALB's SG from modules/network: in from the VPC link SG only, out to the HTTP services only. | `string` | n/a | yes |
| <a name="input_service_port"></a> [service\_port](#input\_service\_port) | Port every HTTP service listens on (each Dockerfile EXPOSEs 8080; the SG rules in modules/network allow it). | `number` | `8080` | no |
| <a name="input_subnet_ids"></a> [subnet\_ids](#input\_subnet\_ids) | The app subnets, one per AZ (D3). | `list(string)` | n/a | yes |
| <a name="input_vpc_id"></a> [vpc\_id](#input\_vpc\_id) | The VPC the target groups live in. | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_arn"></a> [arn](#output\_arn) | ALB ARN: the REST integration's target (D5). |
| <a name="output_arn_suffix"></a> [arn\_suffix](#output\_arn\_suffix) | ARN suffix, the LoadBalancer dimension of the AWS/ApplicationELB metrics. |
| <a name="output_dns_name"></a> [dns\_name](#output\_dns\_name) | Internal DNS name, the host in the REST integration's URI. |
| <a name="output_listener_arn"></a> [listener\_arn](#output\_listener\_arn) | The HTTP listener, which the services' path rules attach to. |
| <a name="output_target_group_arn_suffixes"></a> [target\_group\_arn\_suffixes](#output\_target\_group\_arn\_suffixes) | Target group ARN suffix per routed service: the TargetGroup dimension, and half of the ALBRequestCountPerTarget resource label. |
| <a name="output_target_group_arns"></a> [target\_group\_arns](#output\_target\_group\_arns) | Target group ARN per routed service, for ecs-service's load\_balancer block. |
<!-- END_TF_DOCS -->
