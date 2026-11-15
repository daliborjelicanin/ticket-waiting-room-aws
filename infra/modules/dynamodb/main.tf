# One on-demand DynamoDB table with every Phase 1 silent default pinned (retro §3, D24).
# Called once per table from the root; the schema itself stays in the root call so it sits
# beside the Java contract it mirrors (SeatTableSchema, ConnectionTableSchema).

locals {
  # Every key attribute, of the table and of its indexes, must be declared, and nothing else.
  key_attributes = distinct(concat(
    [var.hash_key],
    var.range_key == null ? [] : [var.range_key],
    flatten([for i in var.global_secondary_indexes : compact([i.hash_key, i.range_key])]),
  ))
}

resource "aws_dynamodb_table" "this" {
  name         = var.name
  billing_mode = "PAY_PER_REQUEST" # nothing standing to pay for between sessions
  hash_key     = var.hash_key
  range_key    = var.range_key

  dynamic "attribute" {
    for_each = local.key_attributes

    content {
      name = attribute.value
      type = "S"
    }
  }

  dynamic "global_secondary_index" {
    for_each = var.global_secondary_indexes

    content {
      name            = global_secondary_index.value.name
      hash_key        = global_secondary_index.value.hash_key
      range_key       = global_secondary_index.value.range_key
      projection_type = global_secondary_index.value.projection_type
    }
  }

  # Off by default, which Phase 1 found only because holds never expired (retro §3). The
  # value must be epoch *seconds* as a Number: milliseconds are accepted and never expire.
  ttl {
    enabled        = true
    attribute_name = var.ttl_attribute
  }

  point_in_time_recovery {
    enabled                 = var.point_in_time_recovery
    recovery_period_in_days = var.point_in_time_recovery ? var.pitr_recovery_days : null
  }

  # AWS-side rather than prevent_destroy: it also stops the console, and a variable can
  # drive it. End of phase: flip it, apply, destroy (D24).
  deletion_protection_enabled = var.deletion_protection

  # AWS-owned key on purpose (D11): no personal data beyond a pseudonymous sub, and a CMK
  # would put a key-state dependency on the hottest conditional-write path.
  server_side_encryption {
    enabled = false
  }
}
