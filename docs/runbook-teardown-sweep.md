# Runbook — Full Teardown Sweep

Slice 8. Every session ended with its own runbook's teardown and a quick check of the
hourly resources. This is the final, account-wide check that those teardowns added up to
nothing billing, plus a list of the leftovers that do not bill but should not cross into
Phase 2, where Terraform would collide with them or import them by accident.

`scripts/teardown-sweep.sh` is the sweep. It only reads, makes no Cost Explorer call
(each `ce` request is billed at $0.01), and exits 1 when anything in the BILLING tier is
found, so Phase 2 can run it after every `terraform destroy`.

```bash
./scripts/teardown-sweep.sh                 # eu-central-1, plus the us-east-1 globals
ALL_REGIONS=1 ./scripts/teardown-sweep.sh   # also every enabled region, ~2 min
```

## Why A Tag Search Alone Is Not The Sweep

The plan's line was "tag-based search for stragglers", and the tag search is where the
sweep starts, but it can only find what was tagged. Run against this account it returned
59 ARNs and missed **15 project resources**, among them a whole Lambda function
(`qr-generation`) and the first ECR repository (`seat-inventory-service`). The console tags
whatever you type into its Tags box, and slices 1–3 often did not. The index is also
eventually consistent in the other direction: it can keep entries for deleted resources.
So the script has three tiers:

| Tier | What it covers | Response |
|---|---|---|
| **BILLING** | Things that bill by the hour or month whether used or not: ECS tasks, load balancers, RDS (and its snapshots and retained backups), ElastiCache (and snapshots), WAF ACLs, NAT, EIPs and any public IPv4, EC2/EBS, interface endpoints, VPC links, stage caches, EventBridge connections and other secrets, customer KMS keys, provisioned concurrency, enabled schedules, running executions, non-on-demand tables, CloudFront, hosted zones | Exit 1. Tear it down |
| **STANDING** | Billed by size: ECR against the 500 MB free tier, S3 objects, log groups that never expire, active task-definition revisions | Reported. Judge it |
| **TAGS** | Project-named Lambdas, repositories, queues, tables, log groups, security groups and schedules that are missing from the tag index, plus the API Gateway APIs actually present (a stale index entry looks like a straggler) | Tag it or delete it |

The BILLING tier queries each service directly, without going through tags, because the
expensive mistakes (an ALB, a stopped-but-billing RDS, a replication group's leftover node)
are exactly the ones that a tag would not have saved.

## Results, 2026-10-05

**Nothing billing.** Every BILLING check passed in eu-central-1, and `ALL_REGIONS=1` found no
tagged resource, EC2 instance, load balancer or RDS instance in any other region. The
project tag appears outside the home region only on the two global resources in
us-east-1's index (the `ticket-waiting-room` dashboard and the `ticket-telemetry-export`
policy). October to date: **$0.02 actual, $0.32 forecast** (both budgets, $10 and $1).

September's bill by service, as the input to Phase 2's cost model: ECR $0.13, VPC $0.14
(public IPv4 on tasks and the ALB), ELB $0.14, RDS $0.13, ECS $0.08, ElastiCache $0.07,
Cost Explorer $0.06 (the per-request charge, from checking the bill), Cognito $0.02, API
Gateway $0.02, everything else under a cent, tax $0.16.

**Leftovers that do not bill, and what to do with each.** None of these were deleted. Each
is listed with the command, to run when you decide, not by the sweep:

| Leftover | Why it is a straggler | Proposed action |
|---|---|---|
| ECR: the `5e3c555` image in five service repos | Superseded by `272e582` (the load-test rebuild); nothing references it. It is why ECR is **2.14 GB ≈ $0.16/mo**, against the README's ≈ $0.07 | `aws ecr batch-delete-image --repository-name <repo> --image-ids imageTag=5e3c555` ×5 → ~1.1 GB |
| HTTP APIs `inventory-api`, `checkout-api`, `issuance-api` | Slice 4–5's HTTPS front door for `http:invoke` (`runbook-checkout-saga.md`), superseded in slice 6 by `ticket-edge`. Their integrations point at task IPs that no longer exist | `aws apigatewayv2 delete-api --api-id <id>` for `<http-api-id>`, `<http-api-id>`, `<http-api-id>` |
| IAM role `qr-generation-role-r4yviiqj` | Created by the Lambda console's default, replaced by `ticket-qr-generation-lambda-role` the same day; last used 2026-08-31 | detach its policies, `aws iam delete-role` |
| IAM role `rds-monitoring-role` | Enhanced Monitoring role from the RDS wizard; never used | detach, `aws iam delete-role` |
| 24 active task-definition revisions | Only the latest per family is referenced. `seat-inventory-service:1` and `:2` still name `ecsTaskExecutionRole`, deleted 2026-08-23, so they would fail at launch. `netdiag:1` (`postgres:16-alpine`) is the throwaway task from `runbook-checkout-rds.md`, *Proving the network path independently*, never named there | `aws ecs deregister-task-definition` for all but `seat-inventory:6`, `waiting-room:5`, `checkout:10`, `issuance:8`, `notification:4` |
| 15 untagged project resources | `qr-generation` function, `seat-inventory-service` repo, six log groups, six security groups, `hold-seat-schedule` | Tag them only if Phase 1 stays standing; moot if Phase 2 starts clean (see the retro) |
| `ticket-waiting-room-cache-subnets` | ElastiCache subnet group, free, kept for the next cluster | Keep until Phase 2 |
| 52 Cognito users | The 50 load-test users plus alice and bob. Free: billing is per *active* user | `load-test/scripts/delete-test-users.sh 50` |

**Not this project, so not touched:** `lambda-api-gateway-API` (HTTP API from 2024-04, untagged,
its integration names a `lambda-api-gateway` function that no longer exists). It is a
leftover from the same unrelated project as the `ecsTaskExecutionRole` that slice 3's sweep
removed. Delete it when you decide to.

## Traps Hit While Writing The Sweep

- **A name match is not an ARN match.** The first version looked for each name at the end of
  any tagged ARN, so `qr-generation`, the function, counted as tagged because
  `log-group:/aws/lambda/qr-generation` is. Each kind now matches on its own ARN shape
  (`:function:`, `:repository/`, `:table/`, …).
- **Security groups are indexed by id.** The tag index holds `security-group/sg-…`, so the
  name check flags every group. The script asks EC2 for the tag directly.
- **A location with a space breaks shorthand filters.** `Value=EU (Frankfurt)` in a Pricing
  API filter splits on the space. `Field=regionCode,Value=eu-central-1` does not.

## Phase 2

Run the sweep after every `terraform destroy` of the session stack. If `default_tags` is set
on the provider, the TAGS tier should print nothing. If it prints something, Terraform did
not create that resource, which is worth knowing.
