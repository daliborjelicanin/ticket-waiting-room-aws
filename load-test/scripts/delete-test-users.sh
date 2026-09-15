#!/usr/bin/env bash
# Remove the load-test customers again. Their Redis places and orders are on resources
# that are torn down per session anyway; this only tidies the pool.
#   load-test/scripts/delete-test-users.sh 50
set -euo pipefail
source ~/.ticket-waiting-room/cognito.env
N=${1:?number of users}
REGION=${AWS_DEFAULT_REGION:-eu-central-1}
for i in $(seq 1 "$N"); do
  aws cognito-idp admin-delete-user --region "$REGION" --user-pool-id "$POOL_ID" --username "lt-$i@example.test" 2>/dev/null || true
done
echo "deleted up to $N load-test users"
