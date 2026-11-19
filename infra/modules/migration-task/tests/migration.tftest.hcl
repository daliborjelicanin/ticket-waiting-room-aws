# The migration task (D9, D10, D13): the service image in its migrate profile, as the RDS master,
# told which role to create and with what password, hardened like the services (D7). Plan
# against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_ecs_task_definition.this
    values = { arn = "arn:aws:ecs:eu-central-1:aws:task-definition/twr-test-migrate-checkout:1" }
  }
}

variables {
  name               = "twr-test-migrate-checkout"
  image_uri          = "repo.example/checkout-payment-service@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  task_role_arn      = "arn:aws:iam::aws:role/twr-test-migrate-task"
  execution_role_arn = "arn:aws:iam::aws:role/twr-test-migrate-execution"
  log_group_name     = "/twr/test/ecs/migrate"
  region             = "eu-central-1"
  db_address         = "twr-test-postgres.example.eu-central-1.rds.amazonaws.com"
  database           = "checkout"
  master_secret_arn  = "arn:aws:secretsmanager:eu-central-1:aws:secret:rds!db-00000000-0000-0000-0000-000000000000-AbCdEf"
  app_secret_arn     = "arn:aws:secretsmanager:eu-central-1:aws:secret:twr-test/db/checkout_app-AbCdEf"
}

run "runs_the_migrate_profile_against_the_service_database" {
  command = plan

  assert {
    condition = anytrue([for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment : (
      e.name == "SPRING_PROFILES_ACTIVE" && e.value == "migrate"
    )])
    error_message = "The migrate profile, or the image starts as the service (D10)."
  }

  assert {
    condition = anytrue([for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment : (
      e.name == "DB_URL" && e.value == "jdbc:postgresql://${var.db_address}:5432/checkout?sslmode=require"
    )])
    error_message = "DB_URL names the service's database on the instance, TLS required."
  }

  assert {
    condition     = jsondecode(aws_ecs_task_definition.this.container_definitions)[0].essential
    error_message = "The one container is essential: its exit code is the task's, and the deploy's."
  }
}

run "master_runs_it_and_the_service_secret_names_the_role" {
  command = plan

  # name => valueFrom, flat, for comparison as one string.
  assert {
    condition = jsonencode({ for s in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].secrets : s.name => s.valueFrom }) == jsonencode({
      DB_PASSWORD          = "${var.master_secret_arn}:password::"
      DB_USERNAME          = "${var.master_secret_arn}:username::"
      MIGRATE_APP_PASSWORD = "${var.app_secret_arn}:password::"
      MIGRATE_APP_ROLE     = "${var.app_secret_arn}:username::"
    })
    error_message = "DB_* from the master secret, MIGRATE_APP_* from the service's; exactly these four (D9, D13)."
  }

  assert {
    condition = alltrue([for e in jsondecode(aws_ecs_task_definition.this.container_definitions)[0].environment : (
      !can(regex("(?i)password|secret", e.name))
    )])
    error_message = "No credential in plain environment: secrets go through valueFrom only (D13)."
  }
}

run "hardened_like_the_services" {
  command = plan

  assert {
    condition = (
      aws_ecs_task_definition.this.runtime_platform[0].cpu_architecture == "ARM64"
      && contains(aws_ecs_task_definition.this.requires_compatibilities, "FARGATE")
      && aws_ecs_task_definition.this.network_mode == "awsvpc"
    )
    error_message = "ARM64 Fargate in awsvpc mode, like the image it runs (D7)."
  }

  assert {
    condition = alltrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.readonlyRootFilesystem && !c.privileged && contains(c.linuxParameters.capabilities.drop, "ALL")
      && contains([for m in c.mountPoints : m.containerPath], "/tmp")
    )])
    error_message = "Read-only root with a writable /tmp, not privileged, every capability dropped (D7)."
  }

  assert {
    condition = alltrue([for c in jsondecode(aws_ecs_task_definition.this.container_definitions) : (
      c.logConfiguration.options["awslogs-group"] == var.log_group_name
      && c.logConfiguration.options["mode"] == "non-blocking"
      && c.logConfiguration.options["awslogs-stream-prefix"] == var.database
    )])
    error_message = "awslogs into the migrate group, non-blocking, one stream prefix per database."
  }

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.this.container_definitions)) == 1
    error_message = "One container, no collector: the run lasts seconds."
  }

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.this.container_definitions)[0].portMappings) == 0
    error_message = "No port: the migrate profile runs no web server."
  }
}

run "an_image_by_tag_is_refused" {
  command = plan

  variables {
    image_uri = "repo.example/checkout-payment-service:latest"
  }

  expect_failures = [var.image_uri]
}

run "a_secret_by_name_is_refused" {
  command = plan

  variables {
    app_secret_arn = "twr-test/db/checkout_app"
  }

  expect_failures = [var.app_secret_arn]
}

run "a_database_name_that_needs_quoting_is_refused" {
  command = plan

  variables {
    database = "Checkout-DB"
  }

  expect_failures = [var.database]
}
