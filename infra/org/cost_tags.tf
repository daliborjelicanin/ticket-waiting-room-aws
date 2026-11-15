# Cost-allocation tags (D17). Activation is a payer-account setting, so it lives here, not in
# foundation. AWS accepts a key only after it has appeared on billed usage, about 24 h after
# the first tagged resource, and each key on its own schedule: add a key to the variable once
# Billing lists it (an unknown key fails the apply). Activation applies forward only, which is
# why it must happen before the first session.

resource "aws_ce_cost_allocation_tag" "this" {
  for_each = var.cost_allocation_tag_keys

  tag_key = each.value
  status  = "Active"
}
