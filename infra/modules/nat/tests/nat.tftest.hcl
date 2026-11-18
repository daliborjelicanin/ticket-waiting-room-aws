# Session egress (D4): zonal in AZ a by default, every app route table through it, and the
# regional production shape one switch away. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan
}

variables {
  name                = "twr-test-nat"
  vpc_id              = "vpc-test"
  public_subnet_ids   = ["subnet-pub-a", "subnet-pub-b", "subnet-pub-c"]
  app_route_table_ids = ["rtb-app-a", "rtb-app-b", "rtb-app-c"]
}

run "zonal_in_az_a_with_its_own_address" {
  command = plan

  assert {
    condition = (
      aws_nat_gateway.this.availability_mode == "zonal"
      && aws_nat_gateway.this.subnet_id == "subnet-pub-a"
      && length(aws_eip.this) == 1
    )
    error_message = "Sessions: one zonal gateway in the first public subnet (AZ a), with an EIP (D4)."
  }

  assert {
    condition     = aws_nat_gateway.this.connectivity_type == "public"
    error_message = "Public NAT: the tasks reach AWS APIs on the internet."
  }
}

run "every_app_route_table_egresses_through_it" {
  command = plan

  assert {
    condition     = length(aws_route.app_egress) == 3 && alltrue([for r in aws_route.app_egress : (r.destination_cidr_block == "0.0.0.0/0")])
    error_message = "Each app route table gets a default route; all three AZs use the one gateway."
  }
}

run "regional_needs_no_subnet_and_no_eip" {
  command = plan

  variables {
    mode = "regional"
  }

  assert {
    condition = (
      aws_nat_gateway.this.availability_mode == "regional"
      && aws_nat_gateway.this.vpc_id == "vpc-test"
      && aws_nat_gateway.this.subnet_id == null
      && length(aws_eip.this) == 0
    )
    error_message = "Regional: attached to the VPC, no public subnet, AWS-managed addresses (D4)."
  }
}

run "unknown_mode_refused" {
  command = plan

  variables {
    mode = "per-az"
  }

  expect_failures = [var.mode]
}
