# SES address identities, sandbox (D20 deferred the domain). Creating one sends a verification
# email; the identity is usable only after the owner clicks the link, which Terraform cannot do
# (the same one-time step as the ops-alarms subscription).
#
# The sandbox delivers only between verified addresses, so the sender and the fallback
# recipient must both be identities. Here they are the same address: notification-service
# sends as it (NOTIFICATION_FROM) and, since a buyer's customerRef is not an address, to it
# (NOTIFICATION_TO). A different recipient is one more entry in ses_recipients.

locals {
  ses_sender     = var.alert_email
  ses_recipients = [var.alert_email]
}

resource "aws_sesv2_email_identity" "address" {
  for_each = toset(concat([local.ses_sender], local.ses_recipients))

  email_identity = each.value
}
