# Per-service security groups, SG-to-SG only (D3). The network says what the IAM roles say:
#
#   vpclink → alb → {waiting-room, seat-inventory, checkout, issuance}
#   waiting-room → cache        checkout, issuance, migrate → db
#   notification → (egress only)
#
# Standing in foundation because they are free and session resources attach to them. No rule
# references a human IP (D9).

locals {
  # Workloads that run as ECS tasks. Each gets its own SG; http = behind the ALB.
  workloads = {
    waiting-room   = { http = true }
    seat-inventory = { http = true }
    checkout       = { http = true }
    issuance       = { http = true }
    notification   = { http = false }
    migrate        = { http = false }
  }

  http_workloads = [for k, v in local.workloads : k if v.http]

  cache_clients = ["waiting-room"]
  db_clients    = ["checkout", "issuance", "migrate"]
}

resource "aws_security_group" "workload" {
  for_each = local.workloads

  name        = "${var.name_prefix}-${each.key}"
  description = "ECS tasks of ${each.key} (D3)"
  vpc_id      = module.vpc.vpc_id

  tags = {
    Name = "${var.name_prefix}-${each.key}"
  }
}

resource "aws_security_group" "vpclink" {
  name        = "${var.name_prefix}-vpclink"
  description = "API Gateway VPC link ENIs: may only reach the ALB (D5)"
  vpc_id      = module.vpc.vpc_id

  tags = {
    Name = "${var.name_prefix}-vpclink"
  }
}

resource "aws_security_group" "alb" {
  name        = "${var.name_prefix}-alb"
  description = "Internal ALB: from the VPC link only, to the HTTP services only (D3, D5)"
  vpc_id      = module.vpc.vpc_id

  tags = {
    Name = "${var.name_prefix}-alb"
  }
}

resource "aws_security_group" "cache" {
  name        = "${var.name_prefix}-cache"
  description = "Valkey: from waiting-room only (D3, D8)"
  vpc_id      = module.vpc.vpc_id

  tags = {
    Name = "${var.name_prefix}-cache"
  }
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db"
  description = "Postgres: from checkout, issuance and the migration task only (D3, D9)"
  vpc_id      = module.vpc.vpc_id

  tags = {
    Name = "${var.name_prefix}-db"
  }
}

# --- edge: VPC link → ALB → HTTP services ------------------------------------------------------

resource "aws_vpc_security_group_egress_rule" "vpclink_to_alb" {
  security_group_id            = aws_security_group.vpclink.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
  description                  = "to the internal ALB listener"
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_vpclink" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.vpclink.id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
  description                  = "from the API Gateway VPC link"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_service" {
  for_each = toset(local.http_workloads)

  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.workload[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = var.service_port
  to_port                      = var.service_port
  description                  = "to ${each.key}"
}

resource "aws_vpc_security_group_ingress_rule" "service_from_alb" {
  for_each = toset(local.http_workloads)

  security_group_id            = aws_security_group.workload[each.key].id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.service_port
  to_port                      = var.service_port
  description                  = "from the internal ALB (requests and health checks)"
}

# --- egress to AWS APIs: through the NAT (session) or the gateway endpoints ---------------------

resource "aws_vpc_security_group_egress_rule" "workload_https" {
  for_each = local.workloads

  security_group_id = aws_security_group.workload[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs, ECR, Cognito JWKS (NAT, or the S3/DynamoDB endpoints)"
}

# --- data stores --------------------------------------------------------------------------------

resource "aws_vpc_security_group_egress_rule" "to_cache" {
  for_each = toset(local.cache_clients)

  security_group_id            = aws_security_group.workload[each.key].id
  referenced_security_group_id = aws_security_group.cache.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "to Valkey"
}

resource "aws_vpc_security_group_ingress_rule" "cache_from" {
  for_each = toset(local.cache_clients)

  security_group_id            = aws_security_group.cache.id
  referenced_security_group_id = aws_security_group.workload[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  description                  = "from ${each.key}"
}

resource "aws_vpc_security_group_egress_rule" "to_db" {
  for_each = toset(local.db_clients)

  security_group_id            = aws_security_group.workload[each.key].id
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "to Postgres"
}

resource "aws_vpc_security_group_ingress_rule" "db_from" {
  for_each = toset(local.db_clients)

  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.workload[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "from ${each.key}"
}
