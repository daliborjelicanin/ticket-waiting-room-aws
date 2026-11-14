# Cost-allocation tags (D17). Activation is a payer-account setting, so it lives here, not in
# foundation. AWS accepts a key only after it has appeared on billed usage, about 24 h after
# the first tagged resource, so this stays off until then: flip the variable and apply.
# Activation applies forward only, which is why it must happen before the first session.

resource "aws_ce_cost_allocation_tag" "this" {
  for_each = var.activate_cost_allocation_tags ? toset(["project", "stack", "env"]) : toset([])

  tag_key = each.value
  status  = "Active"
}
