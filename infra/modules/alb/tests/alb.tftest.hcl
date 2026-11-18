# The internal ALB (D5): never internet-facing, invalid headers dropped, and a default action
# that answers for itself. Plan against a mocked provider.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_lb_target_group.service
    values = { arn = "arn:aws:elasticloadbalancing:eu-central-1:aws:targetgroup/twr-test-seat-inventory-tg/0000000000000000" }
  }
}

variables {
  name              = "twr-test-alb"
  subnet_ids        = ["subnet-a", "subnet-b", "subnet-c"]
  security_group_id = "sg-alb"
  name_prefix       = "twr-test"
  vpc_id            = "vpc-test"
  routes = {
    seat-inventory = { priority = 20, paths = ["/events/*/seats/*", "/admin/events/*/seats"] }
  }
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

run "targets_ready_tasks_and_drain_fast" {
  command = plan

  assert {
    condition = (
      aws_lb_target_group.service["seat-inventory"].target_type == "ip"
      && aws_lb_target_group.service["seat-inventory"].port == 8080
      && aws_lb_target_group.service["seat-inventory"].name == "twr-test-seat-inventory-tg"
    )
    error_message = "IP targets on 8080 (awsvpc tasks, pods later), named <prefix>-<service>-tg."
  }

  assert {
    condition     = aws_lb_target_group.service["seat-inventory"].health_check[0].path == "/actuator/health/readiness"
    error_message = "The ALB checks readiness, not plain health: a warming or draining task gets no traffic (D7)."
  }

  assert {
    condition     = tonumber(aws_lb_target_group.service["seat-inventory"].deregistration_delay) == 30
    error_message = "Deregistration delay 30 s, not the default 300 (D7)."
  }
}

run "routes_forward_to_their_service" {
  command = plan

  assert {
    condition = (
      aws_lb_listener_rule.service["seat-inventory"].priority == 20
      && aws_lb_listener_rule.service["seat-inventory"].action[0].type == "forward"
      && length(aws_lb_listener_rule.service["seat-inventory"].condition) == 1
    )
    error_message = "One path condition, forwarding at its own priority."
  }
}

run "no_routes_no_target_groups" {
  command = plan

  variables {
    routes = {}
  }

  assert {
    condition     = length(aws_lb_target_group.service) == 0 && length(aws_lb_listener_rule.service) == 0
    error_message = "Without running services everything ends at the NO_ROUTE 404."
  }
}

run "target_group_name_over_32_refused" {
  command = plan

  variables {
    routes = {
      a-service-with-a-long-name = { priority = 10, paths = ["/x/*"] }
    }
  }

  expect_failures = [var.routes]
}

run "duplicate_priority_refused" {
  command = plan

  variables {
    routes = {
      seat-inventory = { priority = 20, paths = ["/events/*/seats/*"] }
      checkout       = { priority = 20, paths = ["/orders/*"] }
    }
  }

  expect_failures = [var.routes]
}
