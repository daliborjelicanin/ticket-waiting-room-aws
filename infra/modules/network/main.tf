# The VPC (D3). Commodity plumbing comes from terraform-aws-modules/vpc, version-pinned (D1);
# what carries project meaning is here: endpoint policies, per-service security groups, the
# flow-log bucket, and the Phase 3 subnet tags.
#
# No NAT Gateway: egress is a session resource (D4). The session stack adds the gateway and a
# default route to the app route tables this module outputs.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.7.3"

  name = var.name_prefix
  cidr = var.cidr
  azs  = var.azs

  public_subnets   = var.public_subnets
  private_subnets  = var.app_subnets
  database_subnets = var.db_subnets

  public_subnet_suffix   = "public"
  private_subnet_suffix  = "app"
  database_subnet_suffix = "db"

  enable_dns_hostnames = true
  enable_dns_support   = true

  # Egress is session-only (D4).
  enable_nat_gateway = false

  # db tier: its own route table with no default route, and a subnet group for RDS (D3, D9).
  create_database_subnet_route_table     = true
  create_database_internet_gateway_route = false
  create_database_nat_gateway_route      = false
  create_database_subnet_group           = true
  database_subnet_group_name             = "${var.name_prefix}-db"

  # The default SG is emptied: nothing should ever use it (trivy finding otherwise).
  manage_default_security_group  = true
  default_security_group_ingress = []
  default_security_group_egress  = []

  # Phase 3: EKS finds the app subnets for internal load balancers without a network change (D21).
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = "1"
  }

  # Flow logs to S3, the cheap vended-log path (D3).
  enable_flow_log           = true
  flow_log_destination_type = "s3"
  flow_log_destination_arn  = aws_s3_bucket.flow_logs.arn
  flow_log_traffic_type     = "ALL"
  flow_log_file_format      = "parquet"
}
