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

  # --- the services (D7, D21, D25) ---
  # Literal flat maps keyed by service: IntelliJ cannot resolve local.map[key].attr
  # (infra/CLAUDE.md). A service joins var.services' validation once its wiring is here.

  service_repository = {
    seat-inventory = "seat-inventory-service"
  }

  # Listener rules: lower number first, first match wins (runbook-alb-apigateway.md).
  service_route_priority = {
    seat-inventory = 20
  }
  service_route_paths = {
    seat-inventory = ["/events/*/seats/*", "/admin/events/*/seats"]
  }

  # Names and references only, never a typed value (D25).
  service_environment = {
    seat-inventory = {
      SEAT_INVENTORY_TABLE_NAME = local.foundation["dynamodb/seat_table_name"]
      COGNITO_ISSUER_URI        = local.foundation["cognito/issuer_uri"]
      AWS_REGION                = var.region
    }
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

resource "aws_ecs_cluster" "this" {
  count = length(var.services) > 0 ? 1 : 0

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
  count = length(var.services) > 0 ? 1 : 0

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

  environment                = local.service_environment[each.key]
  log_group_name             = local.service_log_group[each.key]
  region                     = var.region
  collector_config_parameter = local.collector_config_parameter

  capacity      = var.capacity
  desired_count = var.desired_count

  load_balancer = contains(keys(local.routes), each.key) ? {
    target_group_arn        = module.alb[0].target_group_arns[each.key]
    target_group_arn_suffix = module.alb[0].target_group_arn_suffixes[each.key]
    alb_arn_suffix          = module.alb[0].arn_suffix
  } : null
}
