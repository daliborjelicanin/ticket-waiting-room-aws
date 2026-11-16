# A table's silent defaults (retro §3, D24): TTL off unless set, recovery and deletion
# protection off unless set, and a key attribute list that must name every key and nothing else.

mock_provider "aws" {
  override_during = plan
}

variables {
  name          = "twr-test-connections"
  hash_key      = "connectionId"
  ttl_attribute = "expiresAt"
  global_secondary_indexes = [
    { name = "by-token", hash_key = "eventId", range_key = "token" },
  ]
}

run "pinned_defaults" {
  command = plan

  assert {
    condition     = aws_dynamodb_table.this.ttl[0].enabled && aws_dynamodb_table.this.ttl[0].attribute_name == "expiresAt"
    error_message = "TTL must be enabled on the named attribute: it is off by default and holds never expired (retro §3)."
  }

  assert {
    condition     = aws_dynamodb_table.this.billing_mode == "PAY_PER_REQUEST"
    error_message = "On-demand: nothing standing to pay for between sessions."
  }

  assert {
    condition = (
      aws_dynamodb_table.this.point_in_time_recovery[0].enabled
      && aws_dynamodb_table.this.point_in_time_recovery[0].recovery_period_in_days == 7
    )
    error_message = "PITR on, 7 days (D24)."
  }

  assert {
    condition     = aws_dynamodb_table.this.deletion_protection_enabled
    error_message = "Deletion protection on by default (D24)."
  }

  assert {
    condition     = !aws_dynamodb_table.this.server_side_encryption[0].enabled
    error_message = "AWS-owned key, not the CMK, on the conditional-write path (D11)."
  }
}

run "key_attributes_are_exactly_the_keys" {
  command = plan

  assert {
    condition     = toset([for a in aws_dynamodb_table.this.attribute : a.name]) == toset(["connectionId", "eventId", "token"])
    error_message = "Every table and index key is declared, and nothing else (DynamoDB rejects unused attributes)."
  }

  assert {
    condition     = alltrue([for a in aws_dynamodb_table.this.attribute : (a.type == "S")])
    error_message = "Key attributes are strings."
  }
}

run "index_is_keys_only_and_keyed_on_token" {
  command = plan

  assert {
    condition = anytrue([for g in aws_dynamodb_table.this.global_secondary_index : (
      g.name == "by-token"
      && g.projection_type == "KEYS_ONLY"
      && toset([for k in g.key_schema : "${k.key_type}=${k.attribute_name}"]) == toset(["HASH=eventId", "RANGE=token"])
    )])
    error_message = "The notifier's index: HASH eventId, RANGE token, keys only."
  }
}

run "sort_key_is_declared_when_given" {
  command = plan

  variables {
    name                     = "twr-test-seat-inventory"
    hash_key                 = "pk"
    range_key                = "sk"
    ttl_attribute            = "holdExpiresAt"
    global_secondary_indexes = []
  }

  assert {
    condition     = aws_dynamodb_table.this.range_key == "sk" && toset([for a in aws_dynamodb_table.this.attribute : a.name]) == toset(["pk", "sk"])
    error_message = "A sort key is set and declared."
  }
}

run "pitr_window_is_validated" {
  command = plan

  variables {
    pitr_recovery_days = 0
  }

  expect_failures = [var.pitr_recovery_days]
}
