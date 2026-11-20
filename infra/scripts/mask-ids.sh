#!/usr/bin/env bash
# A filter: replaces the live account's identifiers with the placeholders CLAUDE.md
# (Secrets and Identifiers) prescribes, by shape, never by value.
#
#   terraform ... | infra/scripts/mask-ids.sh
#
# GitHub masks the AWS_ACCOUNT_ID secret in logs, but not in a PR comment, and it knows nothing
# of API ids, ALB names or network ids; anything CI prints or posts from Terraform passes
# through here first (D14).
exec sed -E \
  -e 's/[0-9]{12}/<account-id>/g' \
  -e 's/\b(sg|subnet|vpc|rtb|igw|nat|eni|vpce|eipalloc|acl)-[0-9a-f]{8,17}\b/<\1-id>/g' \
  -e 's/\b[a-z0-9]{10}\.execute-api\./<api-id>.execute-api./g' \
  -e 's/\b[a-zA-Z0-9-]+\.[a-z0-9-]+\.elb\.amazonaws\.com/ticket-alb-<id>/g' \
  -e 's/\.[a-z0-9]{12}\.([a-z0-9-]+)\.rds\.amazonaws\.com/.<rds-id>.\1.rds.amazonaws.com/g' \
  -e 's/-[a-z0-9]{6}\.serverless\./-<cache-id>.serverless./g' \
  -e 's/\b([0-9]{1,3}\.){3}[0-9]{1,3}\b/<ip>/g'
