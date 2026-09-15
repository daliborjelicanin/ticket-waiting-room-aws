#!/usr/bin/env bash
# Create N load-test customers in the Cognito pool: lt-1@example.test … lt-N@example.test,
# all with the same permanent password. Idempotent — an existing user is left alone.
#
#   load-test/scripts/create-test-users.sh 50
#
# Needs POOL_ID from ~/.ticket-waiting-room/cognito.env. The users are free (Essentials
# tier: 10,000 MAU) and permanent; they cost nothing between sessions and delete-test-users.sh
# removes them when the pool should be tidy again.
set -euo pipefail
source ~/.ticket-waiting-room/cognito.env
N=${1:?number of users}
PASSWORD=${LOAD_TEST_PASSWORD:-correct-horse-battery}
REGION=${AWS_DEFAULT_REGION:-eu-central-1}

created=0; existing=0
for i in $(seq 1 "$N"); do
  u="lt-$i@example.test"
  if aws cognito-idp admin-get-user --region "$REGION" --user-pool-id "$POOL_ID" --username "$u" >/dev/null 2>&1; then
    existing=$((existing + 1))
    continue
  fi
  # SUPPRESS: no invitation mail to an address that does not exist.
  aws cognito-idp admin-create-user --region "$REGION" --user-pool-id "$POOL_ID" --username "$u" \
    --user-attributes Name=email,Value="$u" Name=email_verified,Value=true \
    --message-action SUPPRESS >/dev/null
  aws cognito-idp admin-set-user-password --region "$REGION" --user-pool-id "$POOL_ID" --username "$u" \
    --password "$PASSWORD" --permanent
  created=$((created + 1))
done
echo "load-test users: $created created, $existing already existed (lt-1…lt-$N@example.test)"
