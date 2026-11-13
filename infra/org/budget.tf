# Nothing should ever bill in the management account (D12), so any spend there is an action
# and a $1 budget is the right alarm (unlike in twr-dev, where it would fire every month, D17).
# Filtered to this account: a management-account budget otherwise sees the whole org.

resource "aws_budgets_budget" "management_zero_spend" {
  name         = "twr-management-zero-spend"
  budget_type  = "COST"
  limit_amount = "1.0"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "LinkedAccount"
    values = [local.management_account_id]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 1
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }
}
