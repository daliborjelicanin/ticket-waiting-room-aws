# Runbook: Session Data (Phase 2, slice 3)

Postgres and its migrations as the session stack builds them (D9, D10, D13). This page proves
the first slice 3 item:
- the instance comes up private, TLS-only and on the CMK;
- one task per database creates the database and its service role and migrates it, and does
  nothing the second time;
- each service role can work its own rows and nothing else, checked on the live instance.

Valkey (D8) gets its live check with waiting-room's `REDIS_SSL` work.

What bills while it is up (eu-central-1): the NAT (~$0.05/h plus data), Postgres
`db.t4g.micro` (~$0.02/h) and its 20 GB of gp3 (cents). The two service secrets are prorated
from $0.40/month each. The tasks bill seconds of Fargate. **About $0.07/h, an hour at most.**

## 0. Shell and images

```bash
aws sso login --profile twr-dev
source infra/scripts/tf-env.sh         # on its own, never into a pipe
[ "$AWS_PROFILE" = twr-dev ] && echo ok
```

`tf-env.sh` must list `checkout-payment-service` and `ticket-issuance-service` among the image
digests. Otherwise the plan warns that a database gets no migration task. The images are
pushed as in [`runbook-session-edge.md`](runbook-session-edge.md) §6.0: arm64, tag = commit,
digest into `/twr/dev/images/<repo>`. Both must contain `migration-starter`.

## 1. Apply (≈ 10–12 min, mostly the instance)

```bash
terraform -chdir=infra/live/session plan -var enable_nat=true -var enable_db=true -out=session.tfplan
# read it: the NAT and its routes, the cluster, module.rds (instance, parameter group, two
# secrets and their versions), module.migration["checkout"] and ["ticketing"]; nothing else
terraform -chdir=infra/live/session apply session.tfplan && rm session.tfplan
```

## 2. Migrate, twice each

```bash
infra/scripts/migrate.sh checkout
infra/scripts/migrate.sh ticketing
infra/scripts/migrate.sh checkout       # the second run: a no-op that still resets the password
infra/scripts/migrate.sh ticketing
```

Expected, in each log tail:

| Run | Log lines | Exit |
|---|---|---|
| first | `Created database checkout`, `Created role checkout_app`, `Flyway applied 2 migration(s) to checkout; schema version 2`, `Granted checkout_app DML…`, `Migration complete; exiting` | 0 |
| second | `Database checkout exists`, `Role checkout_app exists; password set from its secret`, `Flyway applied 0 migration(s)…; schema version 2` | 0 |

For ticketing, the first run applies 1 migration and ends at schema version 1. The first
session (2026-11-19) measured 61–78 s per run, image pull included, with Spring itself
starting in 15 s.

The script prints migration-starter's own lines and anything at WARN or worse. On a failure it
also prints the last 40 lines in full. A plain tail would show only Hibernate's startup
output, because the migration logs before it.

- **Exit 1:** the log tail names the failing step. A wrong master password, an SSL refusal or
  a failed migration fails startup, and Boot prints why.
- **Exit 2, "the container never ran":** the stopped reason names the cause.
  - `ResourceInitializationError` on a secret: the migrate execution role and the secret's
    ARN pattern (`modules/iam/ecs.tf`); for the master secret, the `aws:rds:primaryDBInstanceArn` tag condition.
  - `CannotPullContainerError`: the image digest, or no route through the NAT.

## 3. Probe each role on the live instance

```bash
infra/scripts/db-probe.sh checkout
infra/scripts/db-probe.sh ticketing
```

Each run logs in as the service's role with the password from its own secret (`psql` from
`public.ecr.aws/docker/library/postgres:18-alpine`, under the migrate security group). The
throwaway task definition is deleted on exit. Expected, all `ok`, exit 0:

| Check | Expected | Why (D9) |
|---|---|---|
| `read-own`, `write-own` | ALLOWED | DML on its own tables |
| `no-superpowers` | ALLOWED | the role is not superuser, cannot create databases or roles |
| `drop-own` | REFUSED: `must be owner of table …` | the master owns every table |
| `create-table` | REFUSED: `permission denied for schema public` | no DDL |
| `read-history` | REFUSED: `permission denied for table flyway_schema_history` | the migration's record is not the service's |
| `connect-other` | REFUSED: `permission denied for database "…"` | `CONNECT` revoked from `PUBLIC` |

Checked locally before the first session (2026-11-19). The probe's SQL ran against
`postgres:18-alpine` after a real checkout migration, and the results came out exactly as
above.

## 4. Record, destroy, sweep

Record the numbers in the slice 3 item of `docs/phase2-plan.md`:
- each run's exit code and time;
- the migrations each first run applied;
- the probe's results.

Then:

```bash
terraform -chdir=infra/live/session destroy -var enable_nat=true -var enable_db=true
./scripts/teardown-sweep.sh
```

Destroying the instance takes a few minutes. There is no final snapshot and no backups in dev
(D24). The RDS-managed master secret goes with the instance. The service secrets are deleted
at once (`recovery_window_in_days = 0`), so the next session can create them again under the
same names.
