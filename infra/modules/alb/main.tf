# The internal ALB behind the REST API's VPC link (D5). Internal, so nothing reaches it from
# the internet: its only security group admits the VPC link's SG and nothing else (rules in
# modules/network). Plain HTTP on the hop, because it never leaves the VPC and a certificate
# needs the deferred domain (D5, D20).
#
# The listener's default action is a fixed 404 with a body of its own. Path rules forward to
# the running services' target groups (below); anything they do not claim ends here, and the
# body says the ALB answered, which is what the D5 spike checks through the stage.

resource "aws_lb" "this" {
  name               = var.name
  internal           = true
  load_balancer_type = "application"
  subnets            = var.subnet_ids
  security_groups    = [var.security_group_id]

  # Header smuggling defence: a header the ALB cannot parse is dropped, not passed on.
  drop_invalid_header_fields = true
  # Session resource, destroyed after every session.
  enable_deletion_protection = false
  idle_timeout               = var.idle_timeout_seconds
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = jsonencode({ error = "NO_ROUTE" })
      status_code  = "404"
    }
  }
}

# --- the services' routes (D7, D21) -------------------------------------------------------------
# Target groups and path rules live here, not in ecs-service: in Phase 3 the AWS Load Balancer
# Controller's TargetGroupBinding registers pods into these same groups, so only what registers
# targets changes (D21).

resource "aws_lb_target_group" "service" {
  for_each = var.routes

  name        = "${var.name_prefix}-${each.key}-tg"
  vpc_id      = var.vpc_id
  target_type = "ip" # awsvpc tasks, and pods later
  protocol    = "HTTP"
  port        = var.service_port

  # The default is 300 s: every deploy and scale-in would wait five minutes, and a session pays
  # for them. 30 s, which the shutdown order below the ALB fits inside (D7).
  deregistration_delay = 30

  # Readiness, not liveness: a task still warming its JWKS cache or already draining gets no
  # traffic (D7, observability-starter pins the probe groups on).
  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# Lower number first, first match wins: a more specific path takes the lower number
# (runbook-alb-apigateway.md, "Path collisions").
resource "aws_lb_listener_rule" "service" {
  for_each = var.routes

  listener_arn = aws_lb_listener.http.arn
  priority     = each.value.priority

  condition {
    path_pattern {
      values = each.value.paths
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service[each.key].arn
  }
}
