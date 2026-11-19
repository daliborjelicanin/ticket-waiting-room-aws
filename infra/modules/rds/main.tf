# The session's Postgres (D9) and the per-service credentials it is reached with (D13).
#
#   - Private: isolated db subnets, no route out, no public address; only checkout, issuance
#     and the migration task reach it, SG to SG (modules/network, D3).
#   - On the CMK (D11): storage, and the RDS-managed master secret.
#   - The master user is migration-only (D9, D10). Each service logs in as its own role with
#     DML on its own database; its password is minted here and never seen by Terraform state.
#   - Rebuilt every session from Flyway (retro): no backups, no final snapshot, no deletion
#     protection. The production values are one variable each (D24).

# Pins the defaults that matter rather than trusting the engine's, so a changed default is a
# diff, not a surprise.
resource "aws_db_parameter_group" "this" {
  name   = var.identifier
  family = "postgres${split(".", var.engine_version)[0]}"

  # TLS only: a client without it is refused, not downgraded.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # A lock wait longer than deadlock_timeout (1 s) is logged: contention on the orders and
  # payments constraints is part of what the purchase race measures (D9).
  parameter {
    name  = "log_lock_waits"
    value = "1"
  }
}

resource "aws_db_instance" "this" {
  identifier     = var.identifier
  engine         = "postgres"
  engine_version = var.engine_version
  # Minor upgrades, never a major one behind our back (D9).
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false

  instance_class        = var.instance_class
  storage_type          = "gp3"
  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = 0 # no autoscaling: a session never grows past 20 GB

  storage_encrypted = true
  kms_key_id        = var.kms_key_arn

  db_subnet_group_name   = var.db_subnet_group_name
  vpc_security_group_ids = [var.security_group_id]
  publicly_accessible    = false
  availability_zone      = var.multi_az ? null : var.availability_zone
  multi_az               = var.multi_az
  parameter_group_name   = aws_db_parameter_group.this.name

  # The master's password is RDS's, in its own secret on the CMK; Terraform never sees it (D13).
  username                      = var.master_username
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.kms_key_arn

  backup_retention_period   = var.backup_retention_days
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.final_snapshot
  final_snapshot_identifier = var.final_snapshot ? "${var.identifier}-final" : null
  copy_tags_to_snapshot     = true
  delete_automated_backups  = true

  # A session resource: changes land now, not in a maintenance window days later.
  apply_immediately = true

  # Database Insights, standard mode: 7 days of query-level load, free, on the CMK (it holds
  # SQL text). The purchase race is where lock waits and slow statements need explaining.
  database_insights_mode                = "standard"
  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  performance_insights_kms_key_id       = var.kms_key_arn

  # Not chosen (D9): IAM database authentication. A 15-minute token behind a Hikari pool is
  # real code for no gain at this scale; passwords from Secrets Manager instead.
  iam_database_authentication_enabled = false
}

# --- per-service credentials (D9, D13) ------------------------------------------------------
# One login role per service, DML on its own database only; the migration task creates the
# role and sets this password on every run, so the role always matches the secret.

ephemeral "random_password" "service" {
  for_each = var.service_secret_names

  length = 32
  # Alphanumeric only: the value goes into a JDBC URL's world and an ALTER ROLE statement,
  # and 32 characters from 62 is ~190 bits without any quoting to get wrong.
  special = false
}

resource "aws_secretsmanager_secret" "service" {
  for_each = var.service_secret_names

  name        = each.value
  description = "Login of the ${each.key} database role (D9, D13). Minted per session."
  kms_key_id  = var.kms_key_arn

  # Session-only: a name in its 7-day recovery window could not be created again by the next
  # session (D13).
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "service" {
  for_each = var.service_secret_names

  secret_id = aws_secretsmanager_secret.service[each.key].id

  # Write-only: sent to Secrets Manager, never stored in state or shown in a plan (D13).
  # A new value is sent only when the version changes, so bumping it is how a password
  # rotates: bump, migrate, redeploy, in one session.sh run (D23).
  secret_string_wo = jsonencode({
    username = each.key
    password = ephemeral.random_password.service[each.key].result
  })
  secret_string_wo_version = var.service_secret_version
}
