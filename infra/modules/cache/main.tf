# The waiting room's queue store (D8): Valkey, the Linux Foundation fork of Redis OSS, on one
# cache.t4g.micro node. Same wire protocol and commands, so the INCR-scored sorted set,
# ZPOPMIN admission and the join/position/admit Lua scripts run unchanged. Going back to Redis
# OSS is one line (engine).
#
#   - TLS in transit, required: a client without it is refused (spring.data.redis.ssl).
#   - Reachable only from waiting-room's SG (modules/network, D3). No AUTH user: TLS plus SG
#     isolation is Phase 2's line; an RBAC user or IAM auth is the production step, because a
#     queue token is a bearer value (D8). Documented, not built.
#   - At rest on the CMK: the queue holds those bearer tokens (D11's rule, 2026-11-19).
#   - Session-only, no snapshots: the queue of a session ends with it.

resource "aws_elasticache_subnet_group" "this" {
  name        = var.name
  description = "Isolated db subnets: no route out (D3)"
  subnet_ids  = var.subnet_ids
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description          = "Waiting-room queue: sorted sets, Lua admission (D8)"

  engine               = "valkey"
  engine_version       = var.engine_version
  parameter_group_name = "default.valkey${split(".", var.engine_version)[0]}"
  node_type            = var.node_type
  port                 = 6379

  # One node, no replica: the shape that supports TLS and RBAC users (D8, retro §2).
  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false
  # Pinned next to the NAT's AZ, so the one cache node and its clients share a zone (D3).
  preferred_cache_cluster_azs = [var.availability_zone]

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.security_group_id]

  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn

  snapshot_retention_limit   = 0
  auto_minor_version_upgrade = true
  apply_immediately          = true
}
