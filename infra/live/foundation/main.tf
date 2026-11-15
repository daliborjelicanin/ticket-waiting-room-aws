# Standing resources (§2 "What lives where"). Slice 0 is the skeleton every later alert needs:
# the CMK, the ops-alarms topic, the budgets, and the audit trail. Slice 1 adds the rest.

data "aws_iam_policy" "boundary" {
  name = "${local.name_prefix}-boundary" # created by infra/bootstrap (D14)
}

locals {
  name_prefix = "twr-${var.env}"
}

module "kms" {
  source = "../../modules/kms"

  name = local.name_prefix
}

module "observability" {
  source = "../../modules/observability"

  name_prefix = local.name_prefix
  kms_key_arn = module.kms.key_arn
  alert_email = var.alert_email
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
