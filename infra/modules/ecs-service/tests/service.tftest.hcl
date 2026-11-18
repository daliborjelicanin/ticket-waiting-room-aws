# One Fargate service (D7, D21): ARM64, Spot in dev, hardened containers, the collector sidecar,
# a rolled-back failed deployment, readiness-gated ALB registration and scaling ahead of the
# door opening. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_ecs_task_definition.this
    values = { arn = "arn:aws:ecs:eu-central-1:aws:task-definition/twr-test-seat-inventory:1" }
  }
}

variables {
  name                       = "twr-test-seat-inventory"
  cluster_name               = "twr-test"
  container_name             = "seat-inventory"
  image_uri                  = "repo.example/seat-inventory-service@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  task_role_arn              = "arn:aws:iam::aws:role/twr-test-seat-inventory-task"
  execution_role_arn         = "arn:aws:iam::aws:role/twr-test-seat-inventory-execution"
  subnet_ids                 = ["subnet-a", "subnet-b", "subnet-c"]
  security_group_id          = "sg-seat-inventory"
  log_group_name             = "/twr/test/ecs/seat-inventory"
  region                     = "eu-central-1"
  collector_config_parameter = "/twr/test/otel-collector-config"
  collector_image            = "repo.example/mirror-twr-test/aws-observability/aws-otel-collector:v0.50.0"
  environment                = { SEAT_INVENTORY_TABLE_NAME = "twr-test-seat-inventory" }
  load_balancer = {
    target_group_arn        = "arn:aws:elasticloadbalancing:eu-central-1:aws:targetgroup/twr-test-seat-inventory-tg/0000000000000000"
    target_group_arn_suffix = "targetgroup/twr-test-seat-inventory-tg/0000000000000000"
    alb_arn_suffix          = "app/twr-test-alb/0000000000000000"
  }
}

run "arm64_fargate_in_the_app_subnets" {
  command = plan

  assert {
    condition = (
      aws_ecs_task_definition.this.runtime_platform[0].cpu_architecture == "ARM64"
      && contains(aws_ecs_task_definition.this.requires_compatibilities, "FARGATE")
      && aws_ecs_task_definition.this.network_mode == "awsvpc"
    )
    error_message = "ARM64 Fargate in awsvpc mode (D7)."
  }

  assert {
    condition     = !aws_ecs_service.this.network_configuration[0].assign_public_ip
    error_message = "No public IP: tasks reach out through the NAT or the gateway endpoints (D4)."
  }
}

run "containers_hardened" {
  command = plan

  assert {
    condition = alltrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.readonlyRootFilesystem && !c.privileged && contains(c.linuxParameters.capabilities.drop, "ALL")
    )])
    error_message = "Every container: read-only root, not privileged, every capability dropped (D7)."
  }

  assert {
    condition = alltrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.logConfiguration.logDriver == "awslogs"
      && c.logConfiguration.options["mode"] == "non-blocking"
      && c.logConfiguration.options["max-buffer-size"] != ""
      && c.logConfiguration.options["awslogs-group"] == var.log_group_name
    )])
    error_message = "awslogs into the declared group, non-blocking with a pinned buffer (D7)."
  }

  assert {
    condition = anytrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.name == "seat-inventory" && contains([for m in c.mountPoints : m.containerPath], "/tmp")
    )])
    error_message = "A writable /tmp under the read-only root, for Tomcat and the JVM (D7)."
  }

  assert {
    condition = anytrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.name == "seat-inventory" && c.stopTimeout > 30
    )])
    error_message = "stopTimeout above Boot's 30 s shutdown phase, or in-flight requests die mid-shutdown (D7)."
  }
}

run "collector_sidecar_beside_the_service" {
  command = plan

  assert {
    condition = anytrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.name == "aws-otel-collector" && !c.essential && c.secrets[0].name == "AOT_CONFIG_CONTENT"
      && c.secrets[0].valueFrom == var.collector_config_parameter
    )])
    error_message = "The collector runs non-essential, configured from SSM (D16)."
  }

  assert {
    condition = anytrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.name == "seat-inventory"
      && contains([for e in c.environment : "${e.name}=${e.value}"], "TELEMETRY_EXPORT_ENABLED=true")
      && contains([for e in c.environment : "${e.name}=${e.value}"], "OTLP_ENDPOINT=http://localhost:4318")
      && contains([for e in c.environment : "${e.name}=${e.value}"], "SEAT_INVENTORY_TABLE_NAME=twr-test-seat-inventory")
      && c.memory + var.collector_memory == var.memory
    )])
    error_message = "The service exports to the sidecar, keeps its own environment, and its hard limit leaves the collector its share."
  }
}

run "without_collector_no_sidecar_and_no_export" {
  command = plan

  variables {
    collector_config_parameter = null
    collector_image            = null
  }

  assert {
    condition = (
      length(jsondecode(aws_ecs_task_definition.this.container_definitions)) == 1
      && !anytrue([for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment : (e.name == "TELEMETRY_EXPORT_ENABLED")])
    )
    error_message = "No collector: one container, and the service does not try to export."
  }
}

run "failed_deployments_roll_back" {
  command = plan

  assert {
    condition = (
      aws_ecs_service.this.deployment_circuit_breaker[0].enable
      && aws_ecs_service.this.deployment_circuit_breaker[0].rollback
      && aws_ecs_service.this.wait_for_steady_state
      && aws_ecs_service.this.deployment_minimum_healthy_percent == 100
      && aws_ecs_service.this.deployment_maximum_percent == 200
    )
    error_message = "Circuit breaker with rollback, the apply waits for steady state, 100/200 (D7, retro §3)."
  }

  assert {
    condition     = aws_ecs_service.this.enable_execute_command
    error_message = "ECS Exec on: the bastion (D9)."
  }
}

run "spot_in_dev" {
  command = plan

  assert {
    condition = (
      length(aws_ecs_service.this.capacity_provider_strategy) == 1
      && anytrue([for s in aws_ecs_service.this.capacity_provider_strategy : (s.capacity_provider == "FARGATE_SPOT")])
    )
    error_message = "Dev sessions run FARGATE_SPOT only (D7)."
  }
}

run "production_mix_keeps_one_on_demand_task" {
  command = plan

  variables {
    capacity = "FARGATE"
  }

  assert {
    condition = (
      anytrue([for s in aws_ecs_service.this.capacity_provider_strategy : (s.capacity_provider == "FARGATE" && s.base == 1)])
      && anytrue([for s in aws_ecs_service.this.capacity_provider_strategy : (s.capacity_provider == "FARGATE_SPOT" && s.weight > 0)])
    )
    error_message = "FARGATE: base 1 on demand, Spot above it (D7)."
  }
}

run "registered_and_scaled_on_requests" {
  command = plan

  assert {
    condition = (
      anytrue([for lb in aws_ecs_service.this.load_balancer : (lb.container_name == "seat-inventory" && lb.container_port == 8080)])
      && aws_ecs_service.this.health_check_grace_period_seconds > 0
    )
    error_message = "Registered into the target group on the application container's port."
  }

  assert {
    condition = (
      aws_appautoscaling_policy.requests[0].target_tracking_scaling_policy_configuration[0].predefined_metric_specification[0].predefined_metric_type == "ALBRequestCountPerTarget"
      && aws_appautoscaling_policy.requests[0].target_tracking_scaling_policy_configuration[0].predefined_metric_specification[0].resource_label == "app/twr-test-alb/0000000000000000/targetgroup/twr-test-seat-inventory-tg/0000000000000000"
    )
    error_message = "Target tracking on requests per target, labelled <alb suffix>/<target group suffix> (D7)."
  }

  assert {
    condition     = aws_appautoscaling_target.this.min_capacity == var.desired_count && aws_appautoscaling_target.this.max_capacity == 4
    error_message = "Scaling between desired_count and 4 (D7)."
  }
}

run "no_load_balancer_no_request_scaling" {
  command = plan

  variables {
    load_balancer = null
  }

  assert {
    condition = (
      length(aws_ecs_service.this.load_balancer) == 0
      && length(aws_appautoscaling_policy.requests) == 0
      && aws_ecs_service.this.health_check_grace_period_seconds == null
    )
    error_message = "A service with no HTTP traffic gets no target group, no request policy, no grace period."
  }
}

run "scaled_ahead_of_the_door_opening" {
  command = plan

  variables {
    scheduled_scaling = {
      on-sale = { schedule = "at(2026-12-01T09:55:00)", min_capacity = 2, max_capacity = 4 }
    }
  }

  assert {
    condition = (
      aws_appautoscaling_scheduled_action.this["on-sale"].schedule == "at(2026-12-01T09:55:00)"
      && tonumber(aws_appautoscaling_scheduled_action.this["on-sale"].scalable_target_action[0].min_capacity) == 2
    )
    error_message = "A scheduled action raises the floor before the on-sale (D7)."
  }
}

run "tag_instead_of_digest_refused" {
  command = plan

  variables {
    image_uri = "repo.example/seat-inventory-service:latest"
  }

  expect_failures = [var.image_uri]
}

run "stop_timeout_at_boots_phase_refused" {
  command = plan

  variables {
    stop_timeout = 30
  }

  expect_failures = [var.stop_timeout]
}

run "container_definitions_read_back_unchanged" {
  command = plan

  # ECS stores every one of these; one left out is a diff on every plan, and a replaced task
  # definition with it (first apply, 2026-11-18).
  assert {
    condition = alltrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      can(c.environment) && can(c.mountPoints) && can(c.portMappings) && can(c.systemControls)
      && can(c.volumesFrom) && can(c.linuxParameters.capabilities.add)
      && alltrue([for p in c.portMappings : (p.hostPort == p.containerPort)])
    )])
    error_message = "Every container spells out the empty lists and hostPort ECS adds, or the second plan is not clean."
  }
}

run "collector_without_image_refused" {
  command = plan

  variables {
    collector_image = null
  }

  expect_failures = [var.collector_image]
}

run "collector_straight_from_public_ecr_refused" {
  command = plan

  variables {
    collector_image = "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0"
  }

  expect_failures = [var.collector_image]
}
