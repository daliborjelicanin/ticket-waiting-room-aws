# One migration task definition: a service image in its migrate profile (migration-starter),
# run once per deploy before the service is updated (D10). A task definition and nothing else:
# no ECS service, no load balancer, no scaling. infra/scripts/migrate.sh runs it with
# `aws ecs run-task` and waits for the exit code.
#
# The task runs as the RDS master user (D9). It creates the database and the service's login
# role, sets the role's password from the service secret, migrates, grants the role DML, and
# exits 0 or 1. No collector sidecar: the run lasts seconds, and its log says what happened.
#
# Hardened as modules/ecs-service's containers (D7): ARM64, read-only root with a writable
# /tmp, every capability dropped, awslogs non-blocking. No ECS Exec: there is nothing to step
# into, and the run is over before a session could open.

locals {
  # Names and references only, never a credential (D25). sslmode=require: RDS refuses plain
  # connections (rds.force_ssl), and this says so instead of leaving it to the driver's
  # `prefer`. The server certificate is not verified, which would need the RDS CA bundle in
  # the image (a known gap, docs/phase2-plan.md slice 3).
  environment = {
    SPRING_PROFILES_ACTIVE = "migrate"
    DB_URL                 = "jdbc:postgresql://${var.db_address}:${var.db_port}/${var.database}?sslmode=require"
  }

  # JSON keys of the two secrets. The master's runs the migration; the service's names the
  # role it creates and the password it sets (migration-starter's MigrationProperties).
  secrets = {
    DB_USERNAME          = "${var.master_secret_arn}:username::"
    DB_PASSWORD          = "${var.master_secret_arn}:password::"
    MIGRATE_APP_ROLE     = "${var.app_secret_arn}:username::"
    MIGRATE_APP_PASSWORD = "${var.app_secret_arn}:password::"
  }

  # Every empty list ECS adds when it stores a definition is written out, or each plan would
  # replace it (modules/ecs-service, found 2026-11-18).
  container = {
    name      = "migrate"
    image     = var.image_uri
    essential = true

    environment  = [for k in sort(keys(local.environment)) : { name = k, value = local.environment[k] }]
    secrets      = [for k in sort(keys(local.secrets)) : { name = k, valueFrom = local.secrets[k] }]
    portMappings = []

    readonlyRootFilesystem = true
    privileged             = false
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    linuxParameters        = { capabilities = { add = [], drop = ["ALL"] } }
    systemControls         = []
    volumesFrom            = []

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = var.log_group_name
        awslogs-region        = var.region
        awslogs-stream-prefix = var.database
        mode                  = "non-blocking"
        max-buffer-size       = "25m"
      }
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

  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([local.container])

  # Fargate task storage, empty at start: the image's /tmp volume (1777) under the read-only root.
  volume {
    name = "tmp"
  }
}
