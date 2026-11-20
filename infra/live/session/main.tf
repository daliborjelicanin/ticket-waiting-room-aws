# By-the-hour resources, destroyed after every session (§2 "What lives where"). Slice 2 on.
#
# Everything foundation built arrives through SSM under /twr/<env>/foundation/ (D2), never
# through its state. A toggle off means the resource does not exist and costs nothing.

locals {
  name_prefix = "twr-${var.env}"
  ssm_prefix  = "/twr/${var.env}/foundation"

  # The parameters this root reads. Plain strings; the lists are split below.
  foundation_keys = [
    "network/vpc_id",
    "network/public_subnet_ids",
    "network/app_route_table_ids",
    "network/app_subnet_ids",
    "network/sg/alb",
    "network/sg/vpclink",
    "apigw/rest/id",
    "apigw/rest/proxy_resource_id",
    "apigw/rest/proxy_http_method",
    "apigw/rest/queue_resource_id",
    "apigw/rest/queue_path",
    "apigw/rest/purchase_path",
    "apigw/rest/definition_hash",
    "apigw/rest/access_log_arn",
    "apigw/rest/stage_name",
    "waf/acl_name",
    "waf/log_bucket_arn",
    "dynamodb/seat_table_name",
    "cognito/issuer_uri",
    "ecr/mirror/aws-observability/aws-otel-collector",
    "kms/key_arn",
    "network/db_subnet_ids",
    "network/db_subnet_group_name",
    "network/sg/db",
    "network/sg/cache",
    "db/instance_identifier",
    "db/secret_name/checkout_app",
    "db/secret_name/ticketing_app",
    "iam/role/migrate-task",
    "iam/role/migrate-execution",
    "ecr/checkout-payment-service",
    "ecr/ticket-issuance-service",
    "messaging/queue/queue-admissions/url",
    "cognito/user_pool_id",
    "cognito/ops_client_id",
    "cognito/token_url",
    "iam/role/saga",
    "messaging/queue/ticket-issuance/url",
    "messaging/topic/purchase-confirmed/arn",
    "messaging/queue/qr-generation/url",
    "messaging/queue/purchase-confirmed-notifications/url",
    "s3/qr_bucket_name",
    "ses/from_address",
    "ses/fallback_recipient",
  ]

  # Per service: its SG, its two roles, its image repository (D3, D12, D15).
  service_keys = flatten([for s in keys(local.service_repository) : [
    "network/sg/${s}",
    "iam/role/${s}-task",
    "iam/role/${s}-execution",
    "ecr/${local.service_repository[s]}",
  ]])

  foundation          = { for k, p in data.aws_ssm_parameter.foundation : k => p.insecure_value }
  app_subnet_ids      = split(",", local.foundation["network/app_subnet_ids"])
  public_subnet_ids   = split(",", local.foundation["network/public_subnet_ids"])
  app_route_table_ids = split(",", local.foundation["network/app_route_table_ids"])
  db_subnet_ids       = split(",", local.foundation["network/db_subnet_ids"])

  # One login role per service, DML on its own database (D9).
  db_service_roles = ["checkout_app", "ticketing_app"]

  # Per database: the image whose migrate profile owns it, and the role it creates (D10).
  # Literal flat maps, read as local.x[each.key] (infra/CLAUDE.md).
  migration_repository = {
    checkout  = "checkout-payment-service"
    ticketing = "ticket-issuance-service"
  }
  migration_role = {
    checkout  = "checkout_app"
    ticketing = "ticketing_app"
  }
  # A migration task exists only for an image that has been pushed: its definition pins the
  # digest (D15). The check below warns about any database left without one.
  migrations = var.enable_db ? toset([
    for db, repo in local.migration_repository : db if contains(keys(var.image_digests), repo)
  ]) : toset([])
  # Outside the check's message: a for inside an interpolation does not parse in IntelliJ (infra/CLAUDE.md).
  migration_images_missing = join(", ", [
    for db, repo in local.migration_repository : repo if !contains(keys(var.image_digests), repo)
  ])

  # --- the services (D7, D21, D25) ---
  # Literal flat maps keyed by service: IntelliJ cannot resolve local.map[key].attr
  # (infra/CLAUDE.md). A service joins var.services' validation once its wiring is here.

  service_repository = {
    seat-inventory = "seat-inventory-service"
    waiting-room   = "waiting-room-service"
    checkout       = "checkout-payment-service"
    issuance       = "ticket-issuance-service"
    notification   = "notification-service"
  }

  # Listener rules: lower number first, first match wins (runbook-alb-apigateway.md).
  # issuance before checkout: an ALB "*" matches across "/", so checkout's /orders/* would
  # also take /orders/{id}/tickets. notification has no HTTP surface (a queue consumer).
  service_route_priority = {
    waiting-room   = 10
    seat-inventory = 20
    issuance       = 30
    checkout       = 40
  }
  service_route_paths = {
    waiting-room   = ["/events/*/queue", "/events/*/queue/admit", "/events/*/admissions/*"]
    seat-inventory = ["/events/*/seats/*", "/admin/events/*/seats"]
    issuance       = ["/orders/*/tickets", "/tickets/*/qr"]
    checkout       = ["/orders", "/orders/*"]
  }

  # Names and references only, never a typed value (D25).
  service_environment = {
    seat-inventory = {
      SEAT_INVENTORY_TABLE_NAME = local.foundation["dynamodb/seat_table_name"]
      COGNITO_ISSUER_URI        = local.foundation["cognito/issuer_uri"]
      AWS_REGION                = var.region
    }
    # TLS to Valkey: the cluster refuses plaintext (transit_encryption_mode required, D8).
    # ElastiCache's certificate chains to Amazon's root, which the Temurin image trusts.
    # The endpoint is null with enable_cache off, which var.services refuses for this service.
    waiting-room = {
      REDIS_HOST          = one(module.cache[*].primary_endpoint)
      REDIS_PORT          = "6379"
      REDIS_SSL           = "true"
      ADMISSION_QUEUE_URL = local.foundation["messaging/queue/queue-admissions/url"]
      COGNITO_ISSUER_URI  = local.foundation["cognito/issuer_uri"]
      AWS_REGION          = var.region
    }
    # Its own role on its own database (D9); never migrates (D10: migrate.sh did, as master).
    checkout = {
      DB_URL                = local.db_url["checkout"]
      SPRING_FLYWAY_ENABLED = "false"
      COGNITO_ISSUER_URI    = local.foundation["cognito/issuer_uri"]
    }
    issuance = {
      DB_URL                = local.db_url["ticketing"]
      SPRING_FLYWAY_ENABLED = "false"
      ISSUANCE_QUEUE_URL    = local.foundation["messaging/queue/ticket-issuance/url"]
      QR_QUEUE_URL          = local.foundation["messaging/queue/qr-generation/url"]
      QR_BUCKET             = local.foundation["s3/qr_bucket_name"]
      COGNITO_ISSUER_URI    = local.foundation["cognito/issuer_uri"]
      AWS_REGION            = var.region
    }
    # SES sandbox (D20 deferred the domain): both addresses are verified identities.
    # TICKET_BASE_URL is the stage: a QR link in the email goes through the edge (D19).
    notification = {
      NOTIFICATION_QUEUE_URL = local.foundation["messaging/queue/purchase-confirmed-notifications/url"]
      NOTIFICATION_SENDER    = "ses"
      NOTIFICATION_FROM      = local.foundation["ses/from_address"]
      NOTIFICATION_TO        = local.foundation["ses/fallback_recipient"]
      # Every mail to the verified identity while SES is in the sandbox: load-test buyers have
      # made-up addresses the sandbox refuses (found 2026-11-19). Drop it with production access.
      NOTIFICATION_SANDBOX_TO = local.foundation["ses/fallback_recipient"]
      TICKET_BASE_URL         = one(module.rest_stage[*].invoke_url)
      COGNITO_ISSUER_URI      = local.foundation["cognito/issuer_uri"]
      AWS_REGION              = var.region
    }
  }

  # The database services' login, from their own secrets' JSON keys (D13). null with enable_db
  # off, which var.services refuses for these two.
  # Built only with the instance: a null address cannot go into a string template, and with
  # enable_db off every plan failed here (found 2026-11-20; every plan since the wiring had the
  # database on).
  db_url = {
    checkout  = var.enable_db ? "jdbc:postgresql://${module.rds[0].address}:5432/checkout?sslmode=require" : null
    ticketing = var.enable_db ? "jdbc:postgresql://${module.rds[0].address}:5432/ticketing?sslmode=require" : null
  }
  service_secrets = {
    seat-inventory = {}
    waiting-room   = {}
    notification   = {}
    checkout = var.enable_db ? {
      DB_USERNAME = "${module.rds[0].service_secret_arns["checkout_app"]}:username::"
      DB_PASSWORD = "${module.rds[0].service_secret_arns["checkout_app"]}:password::"
    } : {}
    issuance = var.enable_db ? {
      DB_USERNAME = "${module.rds[0].service_secret_arns["ticketing_app"]}:username::"
      DB_PASSWORD = "${module.rds[0].service_secret_arns["ticketing_app"]}:password::"
    } : {}
  }

  # 0.25 vCPU for feature slices, 0.5 for the proof (D7). 1 GB either way: the collector
  # sidecar takes 256 MB of it (runbook-observability.md).
  task_cpu = {
    feature = 256
    proof   = 512
  }

  # Names decided in foundation (D25): the log groups and the collector's parameter.
  service_log_group          = { for s in keys(local.service_repository) : s => "/twr/${var.env}/ecs/${s}" }
  ecs_exec_log_group         = "/twr/${var.env}/ecs-exec"
  collector_config_parameter = "/twr/${var.env}/otel-collector-config"
  # Through the registry's pull-through cache, so the layers skip the NAT (modules/ecr).
  collector_image = "${local.foundation["ecr/mirror/aws-observability/aws-otel-collector"]}:v0.50.0"

  routes = {
    for s in var.services : s => {
      priority = local.service_route_priority[s]
      paths    = local.service_route_paths[s]
    } if contains(keys(local.service_route_priority), s)
  }
}

data "aws_ssm_parameter" "foundation" {
  for_each = toset(concat(local.foundation_keys, local.service_keys))

  name = "${local.ssm_prefix}/${each.key}"
}

# --- egress: NAT for the app tier (D4) ----------------------------------------------------------

module "nat" {
  source = "../../modules/nat"
  count  = var.enable_nat ? 1 : 0

  name                = "${local.name_prefix}-nat"
  mode                = var.nat_mode
  vpc_id              = local.foundation["network/vpc_id"]
  public_subnet_ids   = local.public_subnet_ids
  app_route_table_ids = local.app_route_table_ids
}

# --- edge: REST stage → VPC Link v2 → internal ALB (D5) ----------------------------------------

module "alb" {
  source = "../../modules/alb"
  count  = var.enable_alb ? 1 : 0

  name              = "${local.name_prefix}-alb"
  name_prefix       = local.name_prefix
  vpc_id            = local.foundation["network/vpc_id"]
  subnet_ids        = local.app_subnet_ids
  security_group_id = local.foundation["network/sg/alb"]
  routes            = local.routes
}

module "rest_stage" {
  source = "../../modules/rest-stage"
  count  = var.enable_alb ? 1 : 0

  rest_api_id          = local.foundation["apigw/rest/id"]
  proxy_resource_id    = local.foundation["apigw/rest/proxy_resource_id"]
  proxy_http_method    = local.foundation["apigw/rest/proxy_http_method"]
  definition_hash      = local.foundation["apigw/rest/definition_hash"]
  stage_name           = local.foundation["apigw/rest/stage_name"]
  access_log_group_arn = local.foundation["apigw/rest/access_log_arn"]

  vpc_link_name              = "${local.name_prefix}-edge"
  subnet_ids                 = local.app_subnet_ids
  vpc_link_security_group_id = local.foundation["network/sg/vpclink"]
  alb_arn                    = module.alb[0].arn
  alb_dns_name               = module.alb[0].dns_name

  queue_resource_id = local.foundation["apigw/rest/queue_resource_id"]
  queue_path        = local.foundation["apigw/rest/queue_path"]
  purchase_path     = local.foundation["apigw/rest/purchase_path"]
}

# --- WAF on the stage (D6) ------------------------------------------------------------------------

module "waf" {
  source = "../../modules/waf"
  count  = var.enable_waf ? 1 : 0

  name           = local.foundation["waf/acl_name"]
  resource_arn   = module.rest_stage[0].stage_arn
  log_bucket_arn = local.foundation["waf/log_bucket_arn"]
  stage_name     = local.foundation["apigw/rest/stage_name"]
  buyer_entry_paths = [
    local.foundation["apigw/rest/queue_path"],
    local.foundation["apigw/rest/purchase_path"],
  ]
  baseline_mode       = var.waf_baseline_mode
  loadtest_allow_cidr = var.loadtest_allow_cidr
}

# --- compute: the cluster and the services (D7, D9, D21) ----------------------------------------

# Also with the database alone: the migration tasks run in it (D10). An empty cluster is free.
resource "aws_ecs_cluster" "this" {
  count = length(var.services) > 0 || var.enable_db ? 1 : 0

  name = local.name_prefix

  # Billed per metric and duplicates the services' own meters plus ECS's free metrics (D7).
  setting {
    name  = "containerInsights"
    value = "disabled"
  }

  # Every ECS Exec session into the audit group, written with the task role (D9, D12).
  configuration {
    execute_command_configuration {
      logging = "OVERRIDE"

      log_configuration {
        cloud_watch_log_group_name = local.ecs_exec_log_group
      }
    }
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  count = length(aws_ecs_cluster.this)

  cluster_name       = aws_ecs_cluster.this[0].name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

module "service" {
  source   = "../../modules/ecs-service"
  for_each = toset(var.services)

  name           = "${local.name_prefix}-${each.key}"
  cluster_name   = aws_ecs_cluster_capacity_providers.this[0].cluster_name
  container_name = each.key
  # By digest, resolved at dispatch by tf-env.sh or the workflow (D15).
  image_uri = "${local.foundation["ecr/${local.service_repository[each.key]}"]}@${var.image_digests[local.service_repository[each.key]]}"
  cpu       = local.task_cpu[lookup(var.task_size, each.key, "feature")]
  memory    = 1024

  task_role_arn      = local.foundation["iam/role/${each.key}-task"]
  execution_role_arn = local.foundation["iam/role/${each.key}-execution"]
  subnet_ids         = local.app_subnet_ids
  security_group_id  = local.foundation["network/sg/${each.key}"]

  environment                = merge(local.service_environment[each.key], lookup(var.drill_environment, each.key, {}))
  secrets                    = local.service_secrets[each.key]
  log_group_name             = local.service_log_group[each.key]
  region                     = var.region
  collector_config_parameter = local.collector_config_parameter
  collector_image            = local.collector_image

  capacity      = var.capacity
  desired_count = var.desired_count

  load_balancer = contains(keys(local.routes), each.key) ? {
    target_group_arn        = module.alb[0].target_group_arns[each.key]
    target_group_arn_suffix = module.alb[0].target_group_arn_suffixes[each.key]
    alb_arn_suffix          = module.alb[0].arn_suffix
  } : null
}

# --- data: Postgres and the queue store (D8, D9, D13) --------------------------------------------

# The single-AZ stores sit in the first db subnet's zone, which is the NAT's (D3).
data "aws_subnet" "first_db" {
  id = local.db_subnet_ids[0]
}

module "rds" {
  source = "../../modules/rds"
  count  = var.enable_db ? 1 : 0

  identifier           = local.foundation["db/instance_identifier"]
  engine_version       = var.postgres_version
  kms_key_arn          = local.foundation["kms/key_arn"]
  db_subnet_group_name = local.foundation["network/db_subnet_group_name"]
  security_group_id    = local.foundation["network/sg/db"]
  availability_zone    = data.aws_subnet.first_db.availability_zone

  service_secret_names   = { for role in local.db_service_roles : role => local.foundation["db/secret_name/${role}"] }
  service_secret_version = var.db_secret_version
}

module "cache" {
  source = "../../modules/cache"
  count  = var.enable_cache ? 1 : 0

  name              = "${local.name_prefix}-queue"
  engine_version    = var.valkey_version
  subnet_ids        = local.db_subnet_ids
  security_group_id = local.foundation["network/sg/cache"]
  availability_zone = data.aws_subnet.first_db.availability_zone
  kms_key_arn       = local.foundation["kms/key_arn"]
}

# --- migrations: one task definition per database, run before its service (D10) --------------

module "migration" {
  source   = "../../modules/migration-task"
  for_each = local.migrations

  name               = "${local.name_prefix}-migrate-${each.key}"
  image_uri          = "${local.foundation["ecr/${local.migration_repository[each.key]}"]}@${var.image_digests[local.migration_repository[each.key]]}"
  task_role_arn      = local.foundation["iam/role/migrate-task"]
  execution_role_arn = local.foundation["iam/role/migrate-execution"]
  log_group_name     = "/twr/${var.env}/ecs/migrate"
  region             = var.region

  db_address        = module.rds[0].address
  db_port           = module.rds[0].port
  database          = each.key
  master_secret_arn = module.rds[0].master_secret_arn
  app_secret_arn    = module.rds[0].service_secret_arns[local.migration_role[each.key]]
}

check "migration_images" {
  assert {
    condition     = !var.enable_db || length(local.migrations) == length(local.migration_repository)
    error_message = "enable_db is on, but no image digest is known for ${local.migration_images_missing}: that database gets no migration task. Push the image and re-source tf-env.sh."
  }
}

# --- the checkout saga: the state machine and its connection (D5, D11, D13) ------------------

# The ops client's secret, read from the client itself: the one secret D13 accepts in state.
data "aws_cognito_user_pool_client" "ops" {
  count = var.enable_saga ? 1 : 0

  user_pool_id = local.foundation["cognito/user_pool_id"]
  client_id    = local.foundation["cognito/ops_client_id"]
}

module "saga" {
  source = "../../modules/saga"
  count  = var.enable_saga ? 1 : 0

  name_prefix   = local.name_prefix
  token_url     = local.foundation["cognito/token_url"]
  client_id     = local.foundation["cognito/ops_client_id"]
  client_secret = data.aws_cognito_user_pool_client.ops[0].client_secret

  # One definition, also read by the contract tests (modules/saga).
  definition                   = file("${path.module}/../../../docs/checkout-saga.asl.json")
  role_arn                     = local.foundation["iam/role/saga"]
  issuance_queue_url           = local.foundation["messaging/queue/ticket-issuance/url"]
  purchase_confirmed_topic_arn = local.foundation["messaging/topic/purchase-confirmed/arn"]
  kms_key_arn                  = local.foundation["kms/key_arn"]
  log_group_arn                = data.aws_cloudwatch_log_group.saga[0].arn
}

# Foundation's group, by its D25 name; the plan fails here if it is missing.
data "aws_cloudwatch_log_group" "saga" {
  count = var.enable_saga ? 1 : 0

  name = "/aws/vendedlogs/states/${local.name_prefix}-checkout-saga"
}
