# Runbook: the full session (Phase 2, slice 3)

All five services, their data and the saga, brought up in the order D23 requires by
`infra/scripts/session.sh`, and taken down again. The slice-specific checks build on this:
[`runbook-session-data.md`](runbook-session-data.md) for Postgres and Valkey, and the purchase
and refund paths of [`runbook-checkout-saga.md`](runbook-checkout-saga.md) on AWS.

What bills while it is up (eu-central-1), about **$0.20/h**:
- the NAT (~$0.05/h plus data) and the ALB (~$0.03/h);
- Postgres and Valkey (~$0.02/h each);
- five Fargate Spot tasks at 0.25 vCPU / 1 GB (~$0.03/h together);
- the web ACL (~$0.01/h);
- the secrets, prorated.

The saga bills per state transition. Up takes about 20 minutes, mostly Postgres and Valkey;
down about 15.

## 0. Shell and images

```bash
aws sso login --profile twr-dev
source infra/scripts/tf-env.sh         # on its own, never into a pipe
[ "$AWS_PROFILE" = twr-dev ] && echo ok
```

`tf-env.sh` must list all five service images: `seat-inventory-service`,
`waiting-room-service`, `checkout-payment-service`, `ticket-issuance-service`,
`notification-service`. `session.sh up` refuses to start without them. An image is pushed as
in [`runbook-session-edge.md`](runbook-session-edge.md) §6.0.

## 1. Up

```bash
infra/scripts/session.sh up
```

The three stages, each a separate `terraform apply` that asks for approval
(`AUTO_APPROVE=1` skips it; the workflow does that from slice 4):

| Stage | What | Why first |
|---|---|---|
| 1/3 | NAT, ALB, stage, WAF, Postgres, Valkey, the saga, seat-inventory | the refund path needs seat-inventory's idempotent confirm before the saga runs |
| 2/3 | `migrate.sh checkout`, `migrate.sh ticketing` | each database and its role exist before its service; a failed migration stops here |
| 3/3 | every service | |

Read each plan before approving it:
- **Stage 1** adds everything except the four other services. The saga and its connection may
  already exist from an earlier `enable_saga` apply.
- **Stage 3** adds the four services, their target groups and listener rules, in this order:
  waiting-room 10, seat-inventory 20, issuance 30, checkout 40. Issuance comes before
  checkout because an ALB `*` crosses `/`.

Arguments after `--` reach every apply, e.g. the load test's
`-- -var loadtest_allow_cidr=<my-ip>/32`.

## 2. Is it up?

```bash
aws ecs describe-services --cluster twr-dev \
  --services twr-dev-seat-inventory twr-dev-waiting-room twr-dev-checkout twr-dev-issuance twr-dev-notification \
  --query 'services[].[serviceName,runningCount,desiredCount,deployments[0].rolloutState]' --output table
```

Every service should show 1/1, `COMPLETED`. The apply already waited for steady state, so this
is a read-back.

## 3. Down

```bash
infra/scripts/session.sh down
```

Destroy with every toggle on, then `scripts/teardown-sweep.sh`, which must report
`SWEEP: nothing billing by the hour`.
