# ecs-service

One ECS Fargate service, its task definition and its autoscaling, and nothing else (D21): the cluster, roles, security group, log group and target group are inputs. ARM64, Spot in dev (`capacity = "FARGATE"` is the D7 mix: one on-demand task, Spot above it). Circuit breaker with rollback and `wait_for_steady_state`. Hardened containers: read-only root with writable `/tmp` and the ECS Exec agent's paths, every capability dropped, `awslogs` non-blocking, `stopTimeout` 45 s above Boot's 30 s shutdown phase. The ADOT collector sidecar runs from the SSM config when `collector_config_parameter` is set. Scaling: target tracking on ALB requests per target, plus scheduled actions ahead of a known on-sale (D7).

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
| [aws_appautoscaling_policy.requests](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/appautoscaling_policy) | resource |
| [aws_appautoscaling_scheduled_action.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/appautoscaling_scheduled_action) | resource |
| [aws_appautoscaling_target.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/appautoscaling_target) | resource |
| [aws_ecs_service.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_service) | resource |
| [aws_ecs_task_definition.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ecs_task_definition) | resource |

## Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_capacity"></a> [capacity](#input\_capacity) | FARGATE\_SPOT: every task on Spot (dev sessions). FARGATE: the D7 production mix, one on-demand task (base 1) that is never interrupted and Spot for everything above it. | `string` | `"FARGATE_SPOT"` | no |
| <a name="input_cluster_name"></a> [cluster\_name](#input\_cluster\_name) | The session's cluster. Its name, not its ARN: the autoscaling resource id is service/<cluster>/<service>. | `string` | n/a | yes |
| <a name="input_collector_config_parameter"></a> [collector\_config\_parameter](#input\_collector\_config\_parameter) | SSM parameter name holding the collector configuration (modules/observability), or null for no sidecar. The name, not the ARN: the parameter is in the task's region. | `string` | `null` | no |
| <a name="input_collector_image"></a> [collector\_image](#input\_collector\_image) | ADOT collector image, through the registry's pull-through cache (modules/ecr mirror\_urls) so its layers skip the NAT. A version tag, never latest. Required with collector\_config\_parameter. | `string` | `null` | no |
| <a name="input_collector_memory"></a> [collector\_memory](#input\_collector\_memory) | MiB reserved for the collector out of the task's memory (Phase 1: 256 hard). | `number` | `256` | no |
| <a name="input_container_name"></a> [container\_name](#input\_container\_name) | The application container's name, also its log stream prefix. | `string` | n/a | yes |
| <a name="input_container_port"></a> [container\_port](#input\_container\_port) | Port the application listens on (each Dockerfile EXPOSEs 8080). | `number` | `8080` | no |
| <a name="input_cpu"></a> [cpu](#input\_cpu) | Task CPU units: 256 for feature slices, 512 for the proof (D7). | `number` | `256` | no |
| <a name="input_desired_count"></a> [desired\_count](#input\_desired\_count) | Tasks at start, and the autoscaling minimum. | `number` | `1` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment variables: names and references from module outputs, never a typed value (D21, D25). TELEMETRY\_EXPORT\_ENABLED and OTLP\_ENDPOINT are added when the collector runs. | `map(string)` | `{}` | no |
| <a name="input_execution_role_arn"></a> [execution\_role\_arn](#input\_execution\_role\_arn) | What ECS uses to start the task (image, log stream, collector config, secrets), from modules/iam (D12). | `string` | n/a | yes |
| <a name="input_health_check_grace_period_seconds"></a> [health\_check\_grace\_period\_seconds](#input\_health\_check\_grace\_period\_seconds) | Seconds ECS ignores ALB health after a task starts. Readiness gates the traffic anyway; this only stops ECS replacing a task still starting. Measured 2026-11-18: seat-inventory ready about 55 s after container start on 0.25 vCPU (Boot 46 s, JWKS warm-up 4 s), so 120 is about twice that (D7). | `number` | `120` | no |
| <a name="input_image_uri"></a> [image\_uri](#input\_image\_uri) | <repository-url>@sha256:<digest>. By digest, never by tag (D15). | `string` | n/a | yes |
| <a name="input_load_balancer"></a> [load\_balancer](#input\_load\_balancer) | Target group to register into, from modules/alb, with the ALB's ARN suffix for the request-count scaling metric. null for a service with no HTTP traffic (notification). | <pre>object({<br/>    target_group_arn        = string<br/>    target_group_arn_suffix = string<br/>    alb_arn_suffix          = string<br/>  })</pre> | `null` | no |
| <a name="input_log_group_name"></a> [log\_group\_name](#input\_log\_group\_name) | The service's log group, declared in foundation (modules/observability). The execution role may write only there. | `string` | n/a | yes |
| <a name="input_max_capacity"></a> [max\_capacity](#input\_max\_capacity) | Autoscaling maximum (D7: 4). | `number` | `4` | no |
| <a name="input_memory"></a> [memory](#input\_memory) | Task memory in MiB, shared by the application and the collector sidecar. 1024: with the sidecar's 256 MiB, 512 left the JVM too little (Phase 1, runbook-observability.md). | `number` | `1024` | no |
| <a name="input_name"></a> [name](#input\_name) | Service name and task definition family, twr-<env>-<service> (D25). | `string` | n/a | yes |
| <a name="input_region"></a> [region](#input\_region) | Region of the log group (awslogs-region). | `string` | n/a | yes |
| <a name="input_requests_per_target"></a> [requests\_per\_target](#input\_requests\_per\_target) | Target tracking: ALB requests per task per minute. A placeholder until the proof measures a task's capacity (D7, slice 5). | `number` | `600` | no |
| <a name="input_scheduled_scaling"></a> [scheduled\_scaling](#input\_scheduled\_scaling) | Scale ahead of a known on-sale (D7): name => an at(...) or cron(...) schedule in UTC with the min and max capacity from then on. | <pre>map(object({<br/>    schedule     = string<br/>    min_capacity = number<br/>    max_capacity = number<br/>  }))</pre> | `{}` | no |
| <a name="input_secrets"></a> [secrets](#input\_secrets) | Environment variables resolved by ECS at start: name => Secrets Manager or SSM ARN (D13). The execution role must be allowed to read each one. | `map(string)` | `{}` | no |
| <a name="input_security_group_id"></a> [security\_group\_id](#input\_security\_group\_id) | The service's own SG from modules/network (D3). | `string` | n/a | yes |
| <a name="input_stop_timeout"></a> [stop\_timeout](#input\_stop\_timeout) | Seconds ECS waits after SIGTERM before SIGKILL. Must exceed Boot's 30 s timeout-per-shutdown-phase (observability-starter), or in-flight requests are killed mid-shutdown (D7). | `number` | `45` | no |
| <a name="input_subnet_ids"></a> [subnet\_ids](#input\_subnet\_ids) | The app subnets (D3). Tasks get no public IP; egress is the NAT or the gateway endpoints (D4). | `list(string)` | n/a | yes |
| <a name="input_task_role_arn"></a> [task\_role\_arn](#input\_task\_role\_arn) | The service's own identity, from modules/iam (D12). | `string` | n/a | yes |

## Outputs

| Name | Description |
|------|-------------|
| <a name="output_service_name"></a> [service\_name](#output\_service\_name) | ECS service name (the ServiceName dimension of the AWS/ECS metrics). |
| <a name="output_task_definition_arn"></a> [task\_definition\_arn](#output\_task\_definition\_arn) | The task definition revision the service runs. |
<!-- END_TF_DOCS -->
