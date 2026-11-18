# One ECS Fargate service: task definition, service and autoscaling, and nothing else (D21).
# The cluster is the session root's, the roles modules/iam's, the security group
# modules/network's, the log group foundation's, the target group modules/alb's. Phase 3 swaps
# this module for a Kubernetes Deployment and keeps every one of those.
#
# Pinned on purpose (D7), each a silent default that was wrong for this system:
#   - ARM64, Spot in dev: the two discounts stack.
#   - Readiness-gated ALB, a 30 s deregistration delay (modules/alb), stopTimeout 45 s above
#     Boot's 30 s shutdown phase: a Spot interruption or a scale-in finishes its requests.
#   - Circuit breaker with rollback, and the apply waits for steady state (retro §3).
#   - Read-only root filesystem, every capability dropped, awslogs non-blocking.
#   - ECS Exec on: the bastion (D9).

# The container definitions spell out every empty list ECS adds when it stores them
# (environment, mountPoints, portMappings, systemControls, volumesFrom, capabilities.add,
# hostPort): left out, each one reads back as a difference and every plan replaces the task
# definition (found on the first apply, 2026-11-18).

locals {
  collector = var.collector_config_parameter != null

  # The JVM sizes its heap from its container's hard limit (MaxRAMPercentage in the
  # Dockerfile), so the application gets the task's memory minus what the collector is given.
  app_memory = local.collector ? var.memory - var.collector_memory : var.memory

  environment = merge(
    var.environment,
    local.collector ? {
      TELEMETRY_EXPORT_ENABLED = "true"
      OTLP_ENDPOINT            = "http://localhost:4318" # the sidecar, on the task's loopback
    } : {},
  )

  # Writable paths under a read-only root (D7). /tmp is Tomcat's and the JVM's. The two SSM
  # paths are where ECS Exec's agent writes: AWS documents ECS Exec as unsupported on a
  # read-only root, and these mounts are the workaround AWS re:Post gives (verified per session).
  writable_paths = {
    tmp     = "/tmp"
    ssm-lib = "/var/lib/amazon/ssm"
    ssm-log = "/var/log/amazon/ssm"
  }

  # Capacity provider => weight and base. FARGATE is the D7 production mix: one on-demand task
  # that Spot never interrupts, every task above it on Spot.
  capacity_weight = {
    FARGATE_SPOT = { FARGATE_SPOT = 1 }
    FARGATE      = { FARGATE = 0, FARGATE_SPOT = 1 }
  }
  capacity_base = {
    FARGATE_SPOT = { FARGATE_SPOT = 0 }
    FARGATE      = { FARGATE = 1, FARGATE_SPOT = 0 }
  }

  log_options = {
    awslogs-group  = var.log_group_name
    awslogs-region = var.region
    # A CloudWatch hiccup must not stall the application's threads, which during a door-open
    # looks like a concurrency bug that is not one (D7). The buffer is pinned, not defaulted.
    mode            = "non-blocking"
    max-buffer-size = "25m"
  }

  app_container = {
    name      = var.container_name
    image     = var.image_uri
    essential = true
    memory    = local.app_memory

    # awsvpc: the host port is the container port; written out because ECS stores it so.
    portMappings = [{ containerPort = var.container_port, hostPort = var.container_port, protocol = "tcp" }]

    environment = [for k in sort(keys(local.environment)) : { name = k, value = local.environment[k] }]
    secrets     = [for k in sort(keys(var.secrets)) : { name = k, valueFrom = var.secrets[k] }]

    readonlyRootFilesystem = true
    privileged             = false
    mountPoints            = [for v, p in local.writable_paths : { sourceVolume = v, containerPath = p, readOnly = false }]
    linuxParameters = {
      capabilities       = { add = [], drop = ["ALL"] }
      initProcessEnabled = true # reaps ECS Exec's processes (D9)
    }
    systemControls = []
    volumesFrom    = []

    stopTimeout = var.stop_timeout

    # Start after the collector, so the first spans and meters have somewhere to go.
    dependsOn = local.collector ? [{ containerName = "aws-otel-collector", condition = "START" }] : []

    logConfiguration = {
      logDriver = "awslogs"
      options   = merge(local.log_options, { awslogs-stream-prefix = var.container_name })
    }
  }

  # Traces to X-Ray, metrics as EMF into the metrics log group, configured from SSM (D16).
  # Not essential: a collector failure loses telemetry, never requests.
  collector_container = {
    name      = "aws-otel-collector"
    image     = var.collector_image
    essential = false
    memory    = var.collector_memory

    secrets = [{ name = "AOT_CONFIG_CONTENT", valueFrom = var.collector_config_parameter }]

    readonlyRootFilesystem = true
    privileged             = false
    linuxParameters        = { capabilities = { add = [], drop = ["ALL"] } }
    environment            = []
    mountPoints            = []
    portMappings           = []
    systemControls         = []
    volumesFrom            = []

    # /healthcheck is a binary in the ADOT image that calls the health_check extension.
    healthCheck = {
      command     = ["CMD", "/healthcheck"]
      interval    = 10
      timeout     = 5
      startPeriod = 10
      retries     = 3
    }

    logConfiguration = {
      logDriver = "awslogs"
      options   = merge(local.log_options, { awslogs-stream-prefix = "otel-collector" })
    }
  }
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  task_role_arn            = var.task_role_arn
  execution_role_arn       = var.execution_role_arn

  # Graviton: about 20% less per task-hour, and Spot works on it (D7).
  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode(concat(
    [local.app_container],
    local.collector ? [local.collector_container] : [],
  ))

  # Fargate task storage, empty at start: the writable paths under the read-only root.
  dynamic "volume" {
    for_each = local.writable_paths

    content {
      name = volume.key
    }
  }
}

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_name
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count

  dynamic "capacity_provider_strategy" {
    for_each = local.capacity_weight[var.capacity]

    content {
      capacity_provider = capacity_provider_strategy.key
      weight            = capacity_provider_strategy.value
      base              = local.capacity_base[var.capacity][capacity_provider_strategy.key]
    }
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = var.load_balancer == null ? [] : ["http"]

    content {
      target_group_arn = var.load_balancer.target_group_arn
      container_name   = var.container_name
      container_port   = var.container_port
    }
  }

  health_check_grace_period_seconds = var.load_balancer == null ? null : var.health_check_grace_period_seconds

  # A new revision starts beside the old one and replaces it only once healthy; a revision
  # that never gets healthy is rolled back, and the apply fails instead of hanging (retro §3).
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  wait_for_steady_state = true

  availability_zone_rebalancing = "ENABLED"
  enable_execute_command        = true # the bastion, audited in the cluster's exec log (D9)
  enable_ecs_managed_tags       = true
  propagate_tags                = "SERVICE"

  lifecycle {
    # Autoscaling owns the running count once the service exists; desired_count is the start
    # and the minimum (below).
    ignore_changes = [desired_count]
  }
}

# --- scaling: ahead of the door opening, then tracking (D7) --------------------------------------

resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this.name}"
  min_capacity       = var.desired_count
  max_capacity       = var.max_capacity
}

# Requests per task, for what the schedule did not predict. The waiting room bounds what
# reaches the services behind it, so they scale for the admitted rate, not the crowd (D7).
resource "aws_appautoscaling_policy" "requests" {
  count = var.load_balancer == null ? 0 : 1

  name               = "${var.name}-requests-per-target"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.requests_per_target
    scale_out_cooldown = 60
    scale_in_cooldown  = 300

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${var.load_balancer.alb_arn_suffix}/${var.load_balancer.target_group_arn_suffix}"
    }
  }
}

# Fargate needs about a minute to place a task and the JVM more to warm, so reactive scaling
# alone arrives after the door has opened: raise the floor before a known on-sale (D7).
resource "aws_appautoscaling_scheduled_action" "this" {
  for_each = var.scheduled_scaling

  name               = "${var.name}-${each.key}"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id
  schedule           = each.value.schedule
  timezone           = "UTC"

  scalable_target_action {
    min_capacity = each.value.min_capacity
    max_capacity = each.value.max_capacity
  }
}
