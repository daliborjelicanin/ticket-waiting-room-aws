# The queue store (D8): Valkey on one node, TLS required, at rest on the CMK, pinned to the
# NAT's zone, no snapshots. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan
}

variables {
  name              = "twr-test-queue"
  engine_version    = "9.1"
  subnet_ids        = ["subnet-a", "subnet-b", "subnet-c"]
  security_group_id = "sg-cache"
  availability_zone = "eu-central-1a"
  kms_key_arn       = "arn:aws:kms:eu-central-1:aws:key/00000000-0000-0000-0000-000000000000"
}

run "valkey_on_one_node" {
  command = plan

  assert {
    condition = (
      aws_elasticache_replication_group.this.engine == "valkey"
      && aws_elasticache_replication_group.this.parameter_group_name == "default.valkey9"
      && aws_elasticache_replication_group.this.num_cache_clusters == 1
      && !aws_elasticache_replication_group.this.automatic_failover_enabled
    )
    error_message = "Valkey, its own parameter family, one node (D8)."
  }

  assert {
    condition     = jsonencode(aws_elasticache_replication_group.this.preferred_cache_cluster_azs) == jsonencode(["eu-central-1a"])
    error_message = "The node is pinned to the NAT's zone (D3)."
  }
}

run "tls_required_and_at_rest_on_the_cmk" {
  command = plan

  assert {
    condition = (
      aws_elasticache_replication_group.this.transit_encryption_enabled
      && aws_elasticache_replication_group.this.transit_encryption_mode == "required"
    )
    error_message = "TLS in transit, required: a plaintext client is refused (D8)."
  }

  assert {
    condition = (
      aws_elasticache_replication_group.this.at_rest_encryption_enabled
      && aws_elasticache_replication_group.this.kms_key_id == var.kms_key_arn
    )
    error_message = "At rest on the CMK: the queue holds bearer tokens (D11)."
  }

  assert {
    condition = (
      length(aws_elasticache_replication_group.this.security_group_ids) == 1
      && contains(aws_elasticache_replication_group.this.security_group_ids, "sg-cache")
    )
    error_message = "Behind the cache SG alone (D3)."
  }
}

run "no_snapshots" {
  command = plan

  assert {
    condition     = aws_elasticache_replication_group.this.snapshot_retention_limit == 0
    error_message = "A session's queue ends with the session: no snapshots."
  }
}
