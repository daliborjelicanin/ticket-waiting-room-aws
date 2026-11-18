# Session egress (D4). Nothing in a task starts without it: ECR auth and manifests, CloudWatch
# Logs, Secrets Manager, SSM, SQS/SNS/SES, Cognito's JWKS, the collector's export, ECS Exec.
# The heavy traffic (ECR layers, DynamoDB) takes foundation's gateway endpoints instead, which
# the slice 2 flow-log check proves.
#
#   zonal    (sessions)   one gateway in the first public subnet (AZ a), every app route
#                         table pointing at it. An AZ-a outage takes egress down: accepted.
#   regional (production) one gateway that follows the workloads across AZs, no public
#                         subnet. Bills per AZ it has expanded into (about 3x zonal once
#                         tasks spread) and can take up to an hour to expand: not a session
#                         answer (D4).

resource "aws_eip" "this" {
  count = var.mode == "zonal" ? 1 : 0

  domain = "vpc"

  tags = {
    Name = var.name
  }
}

resource "aws_nat_gateway" "this" {
  availability_mode = var.mode
  connectivity_type = "public"

  # zonal: a subnet and an address. regional: the VPC, with addresses managed by AWS.
  subnet_id     = var.mode == "zonal" ? var.public_subnet_ids[0] : null
  allocation_id = var.mode == "zonal" ? aws_eip.this[0].id : null
  vpc_id        = var.mode == "regional" ? var.vpc_id : null

  tags = {
    Name = var.name
  }
}

resource "aws_route" "app_egress" {
  for_each = toset(var.app_route_table_ids)

  route_table_id         = each.key
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this.id
}
