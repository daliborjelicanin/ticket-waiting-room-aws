# Standing resources (§2 "What lives where"). Slice 0 is the skeleton every later alert needs:
# the CMK, the ops-alarms topic, the budgets, and the audit trail. Slice 1 adds the rest.

data "aws_iam_policy" "boundary" {
  name = "${local.name_prefix}-boundary" # created by infra/bootstrap (D14)
}

locals {
  name_prefix = "twr-${var.env}"

  # Lambda timeouts, in seconds: the lambda-function calls use them, and so do the visibility
  # timeouts of the queues feeding those functions (6x, retro §3). One number, two places.
  lambda_timeouts = {
    hold-cleanup   = 30
    qr-generation  = 60
    queue-notifier = 30
  }

  # Topics that deliver into CMK-encrypted queues; the key grants them (D11). Named here
  # once, so the key policy and the topic cannot disagree.
  sqs_delivering_topics = ["purchase-confirmed"]

  # Names of things built in later pieces or in the session stack, decided once here so the
  # roles that reference them can exist first (D25: deterministic names). The modules that
  # create them take these same values.
  lambda_function_names = {
    hold-cleanup   = "${local.name_prefix}-hold-cleanup"
    qr-generation  = "${local.name_prefix}-qr-generation"
    queue-notifier = "${local.name_prefix}-queue-notifier"
  }

  ecs_services = ["waiting-room", "seat-inventory", "checkout", "issuance", "notification", "migrate"]

  log_groups = {
    ecs      = { for s in local.ecs_services : s => "/twr/${var.env}/ecs/${s}" }
    lambda   = { for f, n in local.lambda_function_names : f => "/aws/lambda/${n}" }
    apigw    = { for a in ["queue-socket", "edge"] : a => "/twr/${var.env}/apigw/${a}" }
    metrics  = "/twr/${var.env}/metrics"
    ecs_exec = "/twr/${var.env}/ecs-exec"
    # /aws/vendedlogs/ keeps Step Functions off the account's logs resource-policy size limit.
    saga = "/aws/vendedlogs/states/${local.name_prefix}-checkout-saga"
  }

  collector_config_parameter = "/twr/${var.env}/otel-collector-config"
  saga_state_machine_name    = "${local.name_prefix}-checkout-saga"
  saga_state_machine_arn     = "arn:${data.aws_partition.current.partition}:states:${data.aws_region.current.region}:${var.account_id}:stateMachine:${local.saga_state_machine_name}"
  waf_acl_name               = "${local.name_prefix}-edge" # the session stack's web ACL (D6)
  # Both APIs' stage. Fixed: it is part of the invoke URL every QR encodes (D19).
  api_stage_name = var.env

  db = {
    instance_identifier = "${local.name_prefix}-postgres"
    service_secret_names = {
      checkout  = "${local.name_prefix}/db/checkout_app"
      ticketing = "${local.name_prefix}/db/ticketing_app"
    }
  }
}

module "kms" {
  source = "../../modules/kms"

  name                  = local.name_prefix
  sqs_delivering_topics = [for t in local.sqs_delivering_topics : "${local.name_prefix}-${t}"]
}

module "observability" {
  source = "../../modules/observability"

  name_prefix = local.name_prefix
  kms_key_arn = module.kms.key_arn
  alert_email = var.alert_email
  repository  = var.github_repository

  log_groups = merge(
    { for s, n in local.log_groups.ecs : "ecs/${s}" => n },
    { metrics = local.log_groups.metrics, saga = local.log_groups.saga },
  )
  ecs_exec_log_group         = local.log_groups.ecs_exec
  collector_config_parameter = local.collector_config_parameter

  queue_names           = module.messaging.queue_names
  visibility_timeouts   = module.messaging.visibility_timeouts
  dlq_names             = module.messaging.dlq_names
  lambda_function_names = local.lambda_function_names
  lambda_log_groups = {
    hold-cleanup   = module.hold_cleanup.log_group_name
    qr-generation  = module.qr_generation.log_group_name
    queue-notifier = module.queue_notifier.log_group_name
  }

  websocket_api_id           = module.websocket_api.api_id
  websocket_stage_name       = local.api_stage_name
  websocket_access_log_group = module.websocket_api.access_log_group_name
  saga_state_machine_arn     = local.saga_state_machine_arn
  seat_table_name            = module.seat_table.name
  waf_acl_name               = local.waf_acl_name
}

module "budgets" {
  source = "../../modules/budgets"

  name_prefix          = local.name_prefix
  alert_email          = var.alert_email
  ops_alarms_topic_arn = module.observability.ops_alarms_topic_arn
  phase_start          = var.phase2_start
  phase_end            = var.phase2_end
}

module "audit" {
  source = "../../modules/audit"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name_prefix              = local.name_prefix
  ops_alarms_topic_arn     = module.observability.ops_alarms_topic_arn
  permissions_boundary_arn = data.aws_iam_policy.boundary.arn
}

module "network" {
  source = "../../modules/network"

  name_prefix = local.name_prefix
}

# --- stores (D11, D24) ----------------------------------------------------------------------
# Attribute names mirror the Java contracts; a mismatch is not an error anywhere, it is a TTL
# or a query that silently never matches.

# common/.../SeatTableSchema: PK EVENT#<id>, SK SEAT#<id>, holdExpiresAt only while HELD.
module "seat_table" {
  source = "../../modules/dynamodb"

  name                = "${local.name_prefix}-seat-inventory"
  hash_key            = "pk"
  range_key           = "sk"
  ttl_attribute       = "holdExpiresAt"
  deletion_protection = var.deletion_protection
}

# queue-socket-lambda/.../ConnectionTableSchema: written by the $connect integration (D18).
module "connections_table" {
  source = "../../modules/dynamodb"

  name          = "${local.name_prefix}-connections"
  hash_key      = "connectionId"
  ttl_attribute = "expiresAt"
  global_secondary_indexes = [
    { name = "by-token", hash_key = "eventId", range_key = "token", projection_type = "KEYS_ONLY" },
  ]
  deletion_protection = var.deletion_protection
}

# A QR is a ticket, a bearer credential: CMK (D11). Not versioned: re-rendered byte-for-byte
# from the ticket id (D24). Expiry because RDS, and so every ticket, is rebuilt per session.
module "qr_bucket" {
  source = "../../modules/s3"

  name            = "${local.name_prefix}-qr"
  kms_key_arn     = module.kms.key_arn
  expiration_days = var.qr_expiration_days
  force_destroy   = true
}

module "ecr" {
  source = "../../modules/ecr"

  name_prefix = local.name_prefix
  repositories = [
    "waiting-room-service",
    "seat-inventory-service",
    "checkout-payment-service",
    "ticket-issuance-service",
    "notification-service",
    "hold-cleanup-lambda",
    "qr-generation-lambda",
    "queue-socket-lambda",
  ]
}

# --- messaging (D11, retro §3) --------------------------------------------------------------
# Producers and consumers, with the Phase 1 numbers they reproduce:
#   ticket-issuance                   saga (Step Functions) -> ticket-issuance-service   30 s
#   qr-generation                     ticket-issuance-service -> qr-generation Lambda   360 s
#   queue-admissions                  waiting-room-service -> queue-notifier Lambda     180 s
#   purchase-confirmed-notifications  purchase-confirmed topic -> notification-service  120 s
# max_receive_count: 3 where a transient failure clears by itself; 5 behind a Lambda, where a
# throttled invoke counts as a receive and must not push a message that succeeded to the DLQ.
module "messaging" {
  source = "../../modules/messaging"

  name_prefix = local.name_prefix
  kms_key_arn = module.kms.key_arn

  queues = {
    ticket-issuance = {
      # one Postgres insert + one SQS send per message
      consumer          = "pump"
      batch_size        = 10
      message_seconds   = 1
      max_receive_count = 3
    }
    qr-generation = {
      consumer          = "lambda"
      function_timeout  = local.lambda_timeouts["qr-generation"]
      max_receive_count = 5
    }
    queue-admissions = {
      consumer          = "lambda"
      function_timeout  = local.lambda_timeouts["queue-notifier"]
      max_receive_count = 5
    }
    purchase-confirmed-notifications = {
      # SES sandbox sends 1/s; 4 s per message leaves room for its throttling retries
      consumer          = "pump"
      batch_size        = 10
      message_seconds   = 4
      max_receive_count = 3
    }
  }

  topics = {
    purchase-confirmed = { queue_subscribers = ["purchase-confirmed-notifications"] }
  }
}

# --- identity and IAM (D12, D13, D19, D24) --------------------------------------------------

module "identity" {
  source = "../../modules/identity"

  name_prefix         = local.name_prefix
  deletion_protection = var.deletion_protection
}

module "iam" {
  source = "../../modules/iam"

  name_prefix              = local.name_prefix
  permissions_boundary_arn = data.aws_iam_policy.boundary.arn
  kms_key_arn              = module.kms.key_arn

  seat_table_arn        = module.seat_table.arn
  connections_table_arn = module.connections_table.arn
  queue_arns            = module.messaging.queue_arns
  topic_arns            = module.messaging.topic_arns
  qr_bucket_arn         = module.qr_bucket.arn
  repository_arns       = module.ecr.repository_arns

  log_groups                 = local.log_groups
  lambda_function_names      = local.lambda_function_names
  collector_config_parameter = local.collector_config_parameter
  db                         = local.db
  saga_state_machine_name    = local.saga_state_machine_name
  ses_from_address           = local.ses_sender

  websocket_api_execution_arn = module.websocket_api.execution_arn
}

# --- Lambdas (D7, D22, retro §3) -------------------------------------------------------------
# Images by digest, from var.image_digests (tf-env.sh resolves them from SSM, D15).
# Every value in an environment is a reference (retro §2): the QR verify URL is the REST API's
# invoke URL, the notifier's endpoint the WebSocket API's management endpoint.

module "hold_cleanup" {
  source = "../../modules/lambda-function"

  function_name  = local.lambda_function_names["hold-cleanup"]
  role_arn       = module.iam.role_arns["hold-cleanup-lambda"]
  image_uri      = "${module.ecr.repository_urls["hold-cleanup-lambda"]}@${var.image_digests["hold-cleanup-lambda"]}"
  timeout        = local.lambda_timeouts["hold-cleanup"]
  log_group_name = local.log_groups.lambda["hold-cleanup"]

  environment = {
    SEAT_TABLE_NAME = module.seat_table.name # SeatTableSchema's table
  }
}

module "qr_generation" {
  source = "../../modules/lambda-function"

  function_name  = local.lambda_function_names["qr-generation"]
  role_arn       = module.iam.role_arns["qr-generation-lambda"]
  image_uri      = "${module.ecr.repository_urls["qr-generation-lambda"]}@${var.image_digests["qr-generation-lambda"]}"
  timeout        = local.lambda_timeouts["qr-generation"]
  log_group_name = local.log_groups.lambda["qr-generation"]

  environment = {
    QR_BUCKET              = module.qr_bucket.name
    TICKET_VERIFY_BASE_URL = module.rest_api.invoke_url # TicketQrObjects.verificationUrl
  }

  sqs_event_source = { queue_arn = module.messaging.queue_arns["qr-generation"] }
}

module "queue_notifier" {
  source = "../../modules/lambda-function"

  function_name  = local.lambda_function_names["queue-notifier"]
  role_arn       = module.iam.role_arns["queue-notifier-lambda"]
  image_uri      = "${module.ecr.repository_urls["queue-socket-lambda"]}@${var.image_digests["queue-socket-lambda"]}"
  timeout        = local.lambda_timeouts["queue-notifier"]
  log_group_name = local.log_groups.lambda["queue-notifier"]

  environment = {
    CONNECTION_TABLE_NAME = module.connections_table.name # ConnectionTableSchema's table
    WEBSOCKET_ENDPOINT    = module.websocket_api.management_endpoint
  }

  sqs_event_source = { queue_arn = module.messaging.queue_arns["queue-admissions"] }
}
