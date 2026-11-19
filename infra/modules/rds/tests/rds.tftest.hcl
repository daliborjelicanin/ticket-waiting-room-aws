# The session's Postgres (D9, D13, D24): private, TLS-only, on the CMK, a migration-only
# master whose password Terraform never sees, and per-service secrets minted write-only.
# Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_secretsmanager_secret.service
    values = { arn = "arn:aws:secretsmanager:eu-central-1:aws:secret:twr-test/db/role-AbCdEf" }
  }
}

mock_provider "random" {}

variables {
  identifier           = "twr-test-postgres"
  engine_version       = "18.6"
  kms_key_arn          = "arn:aws:kms:eu-central-1:aws:key/00000000-0000-0000-0000-000000000000"
  db_subnet_group_name = "twr-test-db"
  security_group_id    = "sg-db"
  availability_zone    = "eu-central-1a"
  service_secret_names = {
    checkout_app  = "twr-test/db/checkout_app"
    ticketing_app = "twr-test/db/ticketing_app"
  }
}

run "private_and_on_the_cmk" {
  command = plan

  assert {
    condition = (
      !aws_db_instance.this.publicly_accessible
      && aws_db_instance.this.storage_encrypted
      && aws_db_instance.this.kms_key_id == var.kms_key_arn
      && length(aws_db_instance.this.vpc_security_group_ids) == 1
      && contains(aws_db_instance.this.vpc_security_group_ids, "sg-db")
    )
    error_message = "Private, encrypted with the CMK, behind the db SG alone (D9, D11)."
  }

  assert {
    condition     = anytrue([for p in aws_db_parameter_group.this.parameter : (p.name == "rds.force_ssl" && p.value == "1" && p.apply_method == "pending-reboot")])
    error_message = "TLS only: rds.force_ssl pinned to 1, as the static parameter RDS stores it as (pending-reboot), or every apply re-applies it."
  }

  assert {
    condition     = aws_db_parameter_group.this.family == "postgres18"
    error_message = "The parameter group family follows the pinned major."
  }

  assert {
    condition     = aws_db_instance.this.auto_minor_version_upgrade && !aws_db_instance.this.allow_major_version_upgrade
    error_message = "Minor upgrades on, never a major one (D9)."
  }
}

run "master_password_is_rdss_own" {
  command = plan

  assert {
    condition = (
      aws_db_instance.this.manage_master_user_password
      && aws_db_instance.this.master_user_secret_kms_key_id == var.kms_key_arn
      && aws_db_instance.this.password == null
    )
    error_message = "RDS manages the master password in its own secret on the CMK; Terraform holds none (D13)."
  }

  assert {
    condition     = !aws_db_instance.this.iam_database_authentication_enabled
    error_message = "IAM database authentication is not chosen (D9)."
  }

  assert {
    condition = (
      aws_db_instance.this.performance_insights_enabled
      && aws_db_instance.this.performance_insights_retention_period == 7
      && aws_db_instance.this.performance_insights_kms_key_id == var.kms_key_arn
      && aws_db_instance.this.database_insights_mode == "standard"
    )
    error_message = "Database Insights standard: 7 days, free, on the CMK (it holds SQL text)."
  }
}

run "service_secrets_minted_write_only" {
  command = plan

  assert {
    condition = alltrue([for k, s in aws_secretsmanager_secret.service : (
      s.kms_key_id == var.kms_key_arn && s.recovery_window_in_days == 0
    )])
    error_message = "Service secrets on the CMK, with no recovery window: the next session reuses the name (D13)."
  }

  assert {
    condition     = aws_secretsmanager_secret.service["checkout_app"].name == "twr-test/db/checkout_app"
    error_message = "Secret names are foundation's, where the execution roles are scoped to them (D25)."
  }

  assert {
    condition = alltrue([for v in values(aws_secretsmanager_secret_version.service) : (
      v.secret_string_wo_version == 1 && v.secret_string == null
    )])
    error_message = "The password goes through secret_string_wo only: never in state or a plan (D13)."
  }
}

run "dev_values_by_default" {
  command = plan

  assert {
    condition = (
      aws_db_instance.this.backup_retention_period == 0
      && !aws_db_instance.this.deletion_protection
      && aws_db_instance.this.skip_final_snapshot
      && !aws_db_instance.this.multi_az
      && aws_db_instance.this.availability_zone == "eu-central-1a"
    )
    error_message = "Dev: rebuilt each session from Flyway, single-AZ in the pinned zone (D9, D24)."
  }
}

run "production_values_one_variable_each" {
  command = plan

  variables {
    multi_az              = true
    backup_retention_days = 7
    deletion_protection   = true
    final_snapshot        = true
  }

  assert {
    condition = (
      aws_db_instance.this.multi_az
      && aws_db_instance.this.backup_retention_period == 7
      && aws_db_instance.this.deletion_protection
      && !aws_db_instance.this.skip_final_snapshot
      && aws_db_instance.this.final_snapshot_identifier == "twr-test-postgres-final"
    )
    error_message = "The production values are reachable one variable each (D24)."
  }
}

run "engine_version_without_minor_refused" {
  command = plan

  variables {
    engine_version = "18"
  }

  expect_failures = [var.engine_version]
}
