#!/usr/bin/env bash
# The "0 double-sold seats" check, in the data stores, after a run.
#
#   load-test/scripts/invariants.sh <eventId>
#
# Reads DynamoDB (seat states) and, through psql in Docker against the session's RDS
# instance, the checkout and ticketing databases. Every line is an invariant; the numbers
# on the right must agree with each other, and the two "must be 0" lines must be 0.
set -euo pipefail
EVENT=${1:?event id}
REGION=${AWS_DEFAULT_REGION:-eu-central-1}
export DOCKER_HOST=${DOCKER_HOST:-unix:///var/run/docker.sock}

echo "== DynamoDB seat-inventory, event $EVENT"
aws dynamodb query --region "$REGION" --table-name seat-inventory \
  --key-condition-expression 'pk = :pk' --expression-attribute-values "{\":pk\":{\"S\":\"EVENT#$EVENT\"}}" \
  --projection-expression '#s, holdId' --expression-attribute-names '{"#s":"status"}' --output json \
| jq -r '.Items | group_by(.status.S) | map("\(.[0].status.S): \(length)") | .[]'
SOLD=$(aws dynamodb query --region "$REGION" --table-name seat-inventory \
  --key-condition-expression 'pk = :pk' --filter-expression '#s = :sold' \
  --expression-attribute-names '{"#s":"status"}' \
  --expression-attribute-values "{\":pk\":{\"S\":\"EVENT#$EVENT\"},\":sold\":{\"S\":\"SOLD\"}}" \
  --select COUNT --query Count --output text)

DB_HOST=$(aws rds describe-db-instances --region "$REGION" --db-instance-identifier ticket-checkout-db --query 'DBInstances[0].Endpoint.Address' --output text)
SECRET_ARN=$(aws rds describe-db-instances --region "$REGION" --db-instance-identifier ticket-checkout-db --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)
DB_PW=$(aws secretsmanager get-secret-value --region "$REGION" --secret-id "$SECRET_ARN" --query SecretString --output text | jq -r .password)
psql() { docker run --rm -e PGPASSWORD="$DB_PW" postgres:16-alpine psql -h "$DB_HOST" -U ticketadmin -d "$1" -tAc "$2"; }

echo "== Postgres checkout"
PAID=$(psql checkout "select count(*) from orders where event_id = '$EVENT' and status = 'PAID'")
FAILED=$(psql checkout "select count(*) from orders where event_id = '$EVENT' and status in ('FAILED','CANCELLED')")
REFUNDED=$(psql checkout "select count(*) from orders where event_id = '$EVENT' and status = 'REFUNDED'")
# Money still held: a capture counts unless a refund of it succeeded.
CAPTURED=$(psql checkout "select count(*) from payments p join orders o on o.id = p.order_id where o.event_id = '$EVENT' and p.status = 'CAPTURED' and not exists (select 1 from refunds r where r.payment_id = p.id and r.status = 'SUCCEEDED')")
# A held capture belongs to a PAID order, and a refunded one to a REFUNDED order; anything else is money adrift.
ORPHAN_CAPTURE=$(psql checkout "select count(*) from payments p join orders o on o.id = p.order_id where o.event_id = '$EVENT' and p.status = 'CAPTURED' and o.status <> case when exists (select 1 from refunds r where r.payment_id = p.id and r.status = 'SUCCEEDED') then 'REFUNDED' else 'PAID' end")
DOUBLE_REFUND=$(psql checkout "select count(*) from (select payment_id from refunds where status in ('PENDING','SUCCEEDED') group by payment_id having count(*) > 1) x")
STUCK_REFUND=$(psql checkout "select count(*) from refunds r join orders o on o.id = r.order_id where o.event_id = '$EVENT' and r.status <> 'SUCCEEDED' and o.status = 'PAID'")
DOUBLE_LIVE=$(psql checkout "select count(*) from (select order_id from payments where status in ('PENDING','CAPTURED') group by order_id having count(*) > 1) x")
SEAT_TWICE=$(psql checkout "select count(*) from (select os.seat_id from order_seats os join orders o on o.id = os.order_id where o.event_id = '$EVENT' and o.status = 'PAID' group by os.seat_id having count(*) > 1) x")
echo "  orders PAID: $PAID   orders FAILED/CANCELLED: $FAILED   orders REFUNDED: $REFUNDED   payments CAPTURED, not refunded: $CAPTURED"

echo "== Postgres ticketing"
TICKETS=$(psql ticketing "select count(*) from tickets where event_id = '$EVENT'")
TICKET_SEAT_TWICE=$(psql ticketing "select count(*) from (select seat_id from tickets where event_id = '$EVENT' group by seat_id having count(*) > 1) x")
echo "  tickets: $TICKETS"

echo "== invariants"
printf "  seats SOLD = orders PAID = payments CAPTURED = tickets :  %s = %s = %s = %s\n" "$SOLD" "$PAID" "$CAPTURED" "$TICKETS"
printf "  seats with two PAID orders   (must be 0): %s\n" "$SEAT_TWICE"
printf "  seats with two tickets       (must be 0): %s\n" "$TICKET_SEAT_TWICE"
printf "  CAPTURED on a non-PAID order (must be 0): %s   (refunded captures belong to REFUNDED orders)\n" "$ORPHAN_CAPTURE"
printf "  orders with two live payments(must be 0): %s\n" "$DOUBLE_LIVE"
printf "  payments refunded twice      (must be 0): %s\n" "$DOUBLE_REFUND"
printf "  refunds owed, not completed  (should be 0 — RefundFailed, a human's job): %s\n" "$STUCK_REFUND"
if [ "$SOLD" = "$PAID" ] && [ "$PAID" = "$CAPTURED" ] && [ "$CAPTURED" = "$TICKETS" ] \
   && [ "$SEAT_TWICE" = 0 ] && [ "$TICKET_SEAT_TWICE" = 0 ] && [ "$ORPHAN_CAPTURE" = 0 ] && [ "$DOUBLE_LIVE" = 0 ] \
   && [ "$DOUBLE_REFUND" = 0 ]; then
  echo "  RESULT: 0 double-sold seats"
else
  echo "  RESULT: INVARIANT VIOLATED — read the lines above"; exit 1
fi
