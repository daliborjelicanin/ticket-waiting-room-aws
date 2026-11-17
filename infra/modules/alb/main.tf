# The internal ALB behind the REST API's VPC link (D5). Internal, so nothing reaches it from
# the internet: its only security group admits the VPC link's SG and nothing else (rules in
# modules/network). Plain HTTP on the hop, because it never leaves the VPC and a certificate
# needs the deferred domain (D5, D20).
#
# The listener's default action is a fixed 404 with a body of its own. Path rules forward to
# the services' target groups (slice 2, ecs-service); anything they do not claim ends here,
# and the body says the ALB answered, which is what the D5 spike checks through the stage.

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
