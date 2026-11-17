# The internal ALB (D5): never internet-facing, invalid headers dropped, and a default action
# that answers for itself. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan
}

variables {
  name              = "twr-test-alb"
  subnet_ids        = ["subnet-a", "subnet-b", "subnet-c"]
  security_group_id = "sg-alb"
}

run "internal_and_hardened" {
  command = plan

  assert {
    condition     = aws_lb.this.internal && aws_lb.this.load_balancer_type == "application"
    error_message = "The ALB is internal: only the VPC link reaches it (D5)."
  }

  assert {
    condition     = aws_lb.this.drop_invalid_header_fields
    error_message = "Invalid header fields are dropped."
  }

  assert {
    condition     = length(aws_lb.this.security_groups) == 1 && contains(aws_lb.this.security_groups, "sg-alb")
    error_message = "Exactly the given SG, no default SG beside it."
  }

  assert {
    condition     = aws_lb.this.idle_timeout > 29
    error_message = "Idle timeout above API Gateway's 29 s."
  }
}

run "unrouted_requests_get_the_albs_own_404" {
  command = plan

  assert {
    condition = (
      aws_lb_listener.http.port == 80
      && aws_lb_listener.http.default_action[0].type == "fixed-response"
      && aws_lb_listener.http.default_action[0].fixed_response[0].status_code == "404"
      && aws_lb_listener.http.default_action[0].fixed_response[0].message_body == jsonencode({ error = "NO_ROUTE" })
    )
    error_message = "The default action is a fixed 404 NO_ROUTE, never a forward to a service."
  }
}

run "name_over_32_refused" {
  command = plan

  variables {
    name = "twr-test-a-name-that-is-far-too-long"
  }

  expect_failures = [var.name]
}
