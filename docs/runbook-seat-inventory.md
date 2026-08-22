# Runbook — Seat Inventory DynamoDB Table

Slice 1's data store: the single table backing `seat-inventory-service`. Built by hand in
the console on 2026-08-22; this file is the record of what was built and why, and the
spec Phase 2's Terraform translates.

## What Exists

| Property | Value |
|---|---|
| Table name | `seat-inventory` |
| Region | `eu-central-1` |
| ARN | `arn:aws:dynamodb:eu-central-1:<account-id>:table/seat-inventory` |
| Partition key | `pk` — String |
| Sort key | `sk` — String |
| Billing mode | `PAY_PER_REQUEST` (on-demand) |
| Table class | `STANDARD` |
| TTL | Enabled on `holdExpiresAt` |
| Encryption | AWS-owned key (no KMS charge) |
| Point-in-time recovery | Disabled |
| Deletion protection | Disabled |
| Tags | `project=ticket-waiting-room` |

**Standing cost: $0.** On-demand bills per request, and an idle table with no items and no
PITR accrues nothing. This table is meant to survive session teardowns.

## Item Shape

Only `pk` and `sk` are declared in the table schema — DynamoDB requires attribute
definitions for key attributes only, so `status`/`holdId`/`holdExpiresAt` exist purely
per-item. That is why `SeatRepository` can `REMOVE` the TTL attribute on confirm without
anything resembling a migration.

```
PK  = EVENT#<eventId>
SK  = SEAT#<seatId>
status        = AVAILABLE | HELD | SOLD
holdId        = current hold id      (absent when AVAILABLE; kept on SOLD as audit trail)
holdExpiresAt = epoch-seconds Number (present only while HELD)
```

Canonical definition lives in the `SeatRepository` class javadoc; the LocalStack
integration test (`SeatHoldConcurrencyIT.recreateTable`) creates the same table shape,
so local tests and real AWS cannot silently drift.

## Console Steps (as performed)

1. Region selector → **Europe (Frankfurt) eu-central-1**.
2. **DynamoDB → Tables → Create table**
   - Table name `seat-inventory`, partition key `pk` (String), sort key `sk` (String)
   - **Customize settings**
   - Table class: DynamoDB Standard
   - Read/write capacity: **On-demand**
   - Secondary indexes: none
   - Encryption at rest: **Owned by Amazon DynamoDB** — the "AWS managed key" option
     routes through KMS and adds charges this project has no use for
   - Backups → Point-in-time recovery: **Off** (billed per GB; the table is re-seedable)
   - Tags: `project` = `ticket-waiting-room`
3. **Create table**, wait for `Creating` → `Active`.
4. TTL is a **separate operation** — not on the create form:
   **Tables → `seat-inventory` → Additional settings → Time to Live → Turn on**,
   attribute name `holdExpiresAt`.

### CLI Equivalent

```bash
aws dynamodb create-table \
  --table-name seat-inventory \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --tags Key=project,Value=ticket-waiting-room \
  --region eu-central-1

aws dynamodb wait table-exists --table-name seat-inventory --region eu-central-1

aws dynamodb update-time-to-live \
  --table-name seat-inventory \
  --time-to-live-specification "Enabled=true,AttributeName=holdExpiresAt" \
  --region eu-central-1
```

## Verify

```bash
aws dynamodb describe-table --table-name seat-inventory --region eu-central-1 \
  --query 'Table.{Status:TableStatus,Keys:KeySchema,Billing:BillingModeSummary.BillingMode}'

aws dynamodb describe-time-to-live --table-name seat-inventory --region eu-central-1
```

Expect `ACTIVE`, `PAY_PER_REQUEST`, and `TimeToLiveStatus: ENABLED` on `holdExpiresAt`.

The service can be pointed at the real table from a laptop with no Docker or Fargate in
the picture — leave `AWS_DYNAMODB_ENDPOINT` unset and the client falls through to real
AWS via the default credentials chain (`SeatInventoryConfig.dynamoDbClient`):

```bash
./gradlew :seat-inventory-service:bootRun
```

Worth doing before the ECS slice: it separates "table + IAM + region config work" from
"image + task role work", so a later Fargate failure has half the search space.

## TTL Semantics — Why the Cleanup Lambda Is Not Optional

Two properties of DynamoDB TTL drive design decisions elsewhere in this codebase:

**TTL is lazy.** Expired items are deleted "typically within 48 hours", with no
punctuality guarantee. An expired hold can therefore sit in the table, still readable,
long past its expiry. Correctness cannot depend on the deletion having happened — hence
the `holdExpiresAt > :now` clause in `SeatRepository.confirmPurchase`, which rejects an
expired-but-still-present hold at write time. **TTL is garbage collection, not business
logic.**

**TTL deletes the whole item, not the hold.** When TTL does fire on a `HELD` seat, it
erases the seat row entirely rather than resetting it to `AVAILABLE` — inventory would
silently shrink, seat by seat, every time a hold lapsed. Reclaiming expired holds back to
`AVAILABLE` is what slice 2's EventBridge-scheduled cleanup Lambda is for; it is a
correctness requirement, not a tidiness feature.

The same reasoning explains why `confirmPurchase` removes `holdExpiresAt` on sale: a SOLD
seat carrying a live TTL attribute is a sold ticket scheduled for deletion.

**Silent-failure trap:** a typo in the TTL attribute name is accepted without complaint
and TTL simply never fires — no error, no warning, no deletion, ever. Same for a value
stored as a String or as epoch-*milliseconds*: silently ignored. Verify the attribute
name and type after enabling rather than assuming.

## Teardown

**Nothing to tear down.** The table stays between sessions at $0. It is deliberately
excluded from the session teardown checklist — slice 1's teardown covers the ECS service
only.

To reset inventory between experiments, delete and recreate the table (fastest, and
re-running the seed endpoint restores state), or delete the seeded items directly. If the
table is ever deleted, the commands above rebuild it exactly.

## Notes for Phase 2 (Terraform)

- `aws_dynamodb_table` with `billing_mode = "PAY_PER_REQUEST"`, `hash_key = "pk"`,
  `range_key = "sk"`, and `attribute` blocks for the two keys only.
- TTL is an inline `ttl` block in Terraform (`attribute_name = "holdExpiresAt"`,
  `enabled = true`) — Terraform hides the fact that the API needs two separate calls.
- Consider `deletion_protection_enabled = true` once the table is IaC-managed and
  recreating it is no longer a one-liner.