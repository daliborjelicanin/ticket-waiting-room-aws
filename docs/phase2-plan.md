# Phase 2 Plan — Terraform, Production-Shaped, Low-Cost

**Status: all decisions settled (2026-11-11) except D20, deferred. Account structure done by hand
(2026-11-13, D12): Phase 2 runs in the member account `twr-dev`. Slices 0 and 1 done
(2026-11-16): bootstrap, org and the whole foundation stack applied and verified, Phase 1's
hand-built resources destroyed. Next: slice 2.** Every
decision carries an id (**D1**–**D27**; the index is at the top of §3). Changing a settled
decision means editing its entry and dating the change. When a slice is done, tick its boxes
the same way `phase1-plan.md` does.

Phase 2 starts from `phase1-retro.md` and does not repeat it. The retro holds the evidence
(the stack split, the minted values, the defaults that failed silently). This plan holds
the decisions and the order of work.

Goal of this phase: the same logical system as Phase 1, rebuilt **as code**, and raised
to the shape a serious team would run in production: private networking, no shared
secrets at the edge, migrations as a deploy step, alarms, CI/CD with no static keys.
Rebuildable from a cold account by `apply`, and provably torn down by `destroy`.

## Budget Ground Rules

- **Phase 2 total: ≤ $20.** Steady state $10–20/month is acceptable while working, but
  anything billed by the hour lives in the `session` stack and exists only while a
  session is running.
- **Standing cost target: ≈ $2–2.5/month**: the KMS key, alarms beyond the free tier, GuardDuty, ECR and pennies of S3. SSM standard parameters, CloudTrail's
  first trail and Access Analyzer are free.
- **Layered guardrails, all as code** (D17): the hourly *session reaper* (the real brake),
  Infracost on PRs, Cost Anomaly Detection, and budgets: $10 monthly and $20 for the phase,
  which are the record, not the brake.
- **The plan fits $20 only with discipline**: about 40 session-hours, most of them partial
  stacks through the toggles (§6). Re-check §6 against Cost Explorer at the end of every slice.
- **Prices are re-checked from the Price List API in slice 0** (the habit from Phase 1);
  the figures below are estimates for eu-central-1.

---

## 1. Target Topology

*Official AWS icons, generated from [`diagrams/phase2-architecture.py`](diagrams/phase2-architecture.py).
The Mermaid below is the source of truth. Regenerate with
`docker run --rm -v "$PWD:/out" gtramontina/diagrams:0.23.4 docs/diagrams/phase2-architecture.py`.*

![Phase 2 target topology](diagrams/phase2-architecture.png)

What changes from Phase 1 is drawn in **bold** labels. Everything else is carried over
unchanged (see *Carried Over Unchanged* below).

```mermaid
flowchart LR
    Browser["Browser / client · gate scanner"]
    COG["Cognito user pool<br/>+ <b>ticketing/gate</b> scope (D19)"]

    subgraph edge["Regional edge"]
        WAF["WAF<br/><b>queue join + purchase:</b> IP, JA4, token,<br/>Bot Control, anonymous IP · baseline on all (D6)"]
        APIGW["API Gateway REST<br/>REGIONAL"]
        WSAPI["API Gateway WebSocket<br/><b>$connect consumes a ticket</b> (D18)"]
    end

    subgraph vpc["<b>Own VPC — 3 AZs (D3)</b>"]
        subgraph pub["Public subnets"]
            NAT["<b>zonal NAT Gateway</b><br/>session only, AZ a (D4)"]
        end
        subgraph app["Private app subnets"]
            VPCL["<b>VPC Link v2</b> (D5)"]
            ALB["<b>Internal ALB</b><br/>no X-Edge-Secret"]
            subgraph ecs["ECS Fargate — <b>ARM64</b>, 1–2 tasks/service (D7)"]
                WR["waiting-room"]
                SI["seat-inventory"]
                CP["checkout-payment"]
                TI["ticket-issuance"]
                NS["notification"]
                MIG["<b>migration task</b><br/>one-off, per deploy (D10)"]
            end
        end
        subgraph iso["Isolated DB subnets — no route out"]
            REDIS[("<b>Valkey + TLS</b> (D8)")]
            PG[("RDS Postgres<br/><b>private, CMK</b> (D9)")]
        end
        GWE["<b>Gateway endpoints</b><br/>S3 + DynamoDB, free"]
    end

    subgraph regional["Regional services — reached via gateway endpoints or NAT"]
        DDB[("DynamoDB<br/>seat-inventory · connections")]
        S3[("S3 QR bucket")]
        SQS(["SQS queues + DLQs"])
        SNS{{"SNS purchase-confirmed"}}
        SFN["Step Functions saga"]
        LAMBDA["3 Lambdas — <b>ARM64</b>"]
        ECR["ECR"]
        KMS["<b>KMS CMK</b> (D11)"]
        CW["CloudWatch<br/><b>alarms → SNS email</b> (D16)"]
        AUD["<b>CloudTrail · GuardDuty<br/>Access Analyzer</b> (D24, D27)"]
    end

    Browser --> COG
    Browser --> WAF --> APIGW
    Browser -->|"wss ?ticket="| WSAPI -->|"TransactWriteItems:<br/>delete ticket + put connection"| DDB
    WR -->|"issue connect ticket<br/>(conditional cap)"| GWE
    APIGW -->|"private integration"| VPCL --> ALB --> WR & SI & CP & TI
    APIGW -->|"StartExecution"| SFN -->|"http:invoke via stage"| APIGW
    WR --> REDIS
    SI --> GWE --> DDB
    CP & TI --> PG
    MIG --> PG
    TI --> GWE --> S3
    ecs -->|"SQS/SNS/Logs/ECR API/Secrets/Cognito JWKS"| NAT
    SFN --> SQS & SNS
    SQS --> TI & NS & LAMBDA
    KMS -.-> PG & S3 & SQS
    AUD -.-> CW
```

### Network detail (D3, D4)

```mermaid
flowchart TB
    subgraph vpc["VPC 10.20.0.0/16 — 3 AZs"]
        subgraph a["eu-central-1a"]
            pubA["public 10.20.0.0/24<br/>zonal NAT Gateway (session)"]
            appA["app 10.20.16.0/20<br/>ALB node, VPC link ENI, tasks"]
            dbA["db 10.20.4.0/24<br/>RDS, Valkey (pinned to AZ a)"]
        end
        subgraph b["eu-central-1b"]
            pubB["public 10.20.1.0/24<br/>(empty)"]
            appB["app 10.20.32.0/20<br/>ALB node, tasks"]
            dbB["db 10.20.5.0/24"]
        end
        subgraph c["eu-central-1c"]
            pubC["public 10.20.2.0/24<br/>(empty)"]
            appC["app 10.20.48.0/20<br/>ALB node, tasks"]
            dbC["db 10.20.6.0/24"]
        end
        RES["10.20.64.0/18+ reserved for Phase 3"]
    end
    IGW["Internet Gateway"] --- pubA
    appA & appB & appC -->|"0.0.0.0/0 (session only)"| pubA
    appA & appB & appC -->|"prefix lists + endpoint policies"| EP["S3 + DynamoDB gateway endpoints"]
    dbA & dbB & dbC -.-x|"no default route"| IGW
```

Security groups are per service and SG-to-SG only (D3). The VPC link SG feeds the ALB SG,
which feeds only the four HTTP services. Only `waiting-room` reaches the cache, and only
`checkout`, `issuance` and the migration task reach the database. No CIDR rule references a
human IP anywhere (the retro's un-Terraformable `$MYIP` disappears; D9).

---

## 2. Stacks, State and Delivery

*Icons: [`diagrams/phase2-delivery.py`](diagrams/phase2-delivery.py).*

![Phase 2 stacks and delivery](diagrams/phase2-delivery.png)

```mermaid
flowchart LR
    subgraph gh["GitHub"]
        PR["pull request"]
        MAIN["main"]
        WF["Actions workflows"]
    end
    subgraph mgmt["management account — no workloads, no CI"]
        IDC["IAM Identity Center<br/>permission sets"]
        SCP["SCPs on OU workloads<br/>infra/org, applied by hand"]
    end
    subgraph aws["member account twr-dev (OU workloads)"]
        OIDC["IAM OIDC provider"]
        IMGR["role: ci-images<br/>ECR push + /images/* only"]
        PLAN["role: tf-plan<br/>no data-plane reads"]
        APPLYF["role: tf-apply-foundation<br/>env 'foundation', reviewer, IAM under boundary"]
        APPLYS["role: tf-apply-session<br/>env 'session', no IAM writes"]
        subgraph state["S3 state bucket — versioned, use_lockfile"]
            S0["bootstrap.tfstate (local, then migrated)"]
            S1["foundation.tfstate"]
            S2["session.tfstate"]
        end
        SSM["SSM parameters<br/>/twr/dev/foundation/*"]
        FND["foundation stack<br/>≈ $1/mo standing"]
        SES["session stack<br/>≈ $0.30/h, toggles"]
        ECR["ECR — images by digest"]
    end

    PR --> WF -->|"assume (pull_request)"| PLAN
    MAIN --> WF -->|"assume (environment:foundation)"| APPLYF
    WF -->|"assume (environment:session)"| APPLYS
    OIDC --- IMGR & PLAN & APPLYF & APPLYS
    WF -->|"assume (images.yml@main)"| IMGR -->|"push arm64"| ECR
    APPLYF --> FND -->|"publishes outputs"| SSM
    APPLYS --> SES -->|"data sources"| SSM
    IMGR -->|"digests"| IMG["SSM /twr/dev/images/*"] -->|"resolved at dispatch → -var"| SES
    ECR -.-> IMG
    IDC -->|"owner SSO: admin (slices 0–3)"| FND
    SCP -.->|"bounds every principal"| aws
```

### Repository layout (D1, D2)

```
infra/
  org/                management account only, applied by hand: SSO permission sets + assignments, SCPs on OU `workloads`, management-account $1 budget, own state bucket (D12)
  bootstrap/          local state → migrated: state bucket, OIDC provider + sub customisation, tf-plan / tf-apply-foundation / tf-apply-session roles, boundary
  modules/            own modules (one concern each; see D1)
    network/          wraps terraform-aws-modules/vpc + gateway endpoints + SGs
    kms/  dynamodb/  s3/  messaging/  identity/  iam/  ecr/  lambda-function/
    websocket-api/  rest-api/  waf/  alb/  ecs-service/  rds/  cache/
    saga/  observability/  budgets/  audit/ (CloudTrail, GuardDuty, alerts)
      each: main.tf  variables.tf  outputs.tf  versions.tf  README.md (terraform-docs)
            tests/*.tftest.hcl   plan-only module tests with mock_provider (D2)
  live/
    foundation/       applied once, $0–1/mo
    session/          apply at start, destroy at end; per-proof toggles
  scripts/            bash, shebang: session.sh (up|down, the staged order, D23), migrate.sh, smoke.sh, invariants.sh (runs the reconciliation task, D16)
docs/adr/             MADR records for the eight significant decisions (D26)
.github/workflows/    ci.yml, images.yml, terraform.yml, session.yml, session-reaper.yml, drift.yml
.github/dependabot.yml  terraform, github-actions, gradle, docker; weekly, grouped (D2)
```

### What lives where (retro §1, unchanged rule: *references a session value → session*)

| `foundation` (standing ≈ $2.5–3/mo) | `session` (by the hour, toggled) |
|---|---|
| VPC, subnets (EKS-tagged), route tables, IGW, gateway endpoints + policies, per-service SGs, flow logs → S3 | NAT Gateway + its EIP, the private route to it |
| KMS CMK + alias (key policy with every producer's grant) | Internal ALB (HTTP listener, reachable only from the VPC link SG), listener rules, target groups |
| DynamoDB `twr-dev-seat-inventory`, `twr-dev-connections` (PITR, deletion protection) | VPC Link v2, the `/{proxy+}` private integration, deployment + `dev` stage (D5) |
| S3 QR bucket, ECR repos (immutable, lifecycle, scan on push) | RDS instance (+ managed secret on the CMK), per-service DB secrets (write-only, fresh per session), Valkey cluster |
| SQS/DLQs/redrive, SNS + subscriptions | ECS services + task definitions + autoscaling (scheduled + target tracking), migration task definitions |
| Cognito pool (deletion protection), domain, resource server, clients incl. the gate scanner | EventBridge connection, `checkout-saga` state machine |
| IAM roles + policies + permissions boundary, Access Analyzer | WAF web ACL + association + logging |
| 3 Lambdas + ESMs, WebSocket API (connect-ticket template, route throttling) + stage | `hold-seat-schedule` |
| REST API + resources/methods (id and invoke URL stable for the life of foundation) | Session alarms: saga, ALB 5xx, unhealthy/missing tasks (D16) |
| SES address identities (sandbox) | `expires_at` SSM parameter read by the reaper (D15) |
| Log groups, dashboard, foundation alarms, `ops-alarms` topic, budgets, anomaly monitor | |
| CloudTrail trail + bucket, GuardDuty detector, EventBridge security alerts | |

Toggles in `session`: `enable_nat`, `enable_alb`, `enable_db`, `enable_cache`, `enable_waf`,
`enable_saga`, `services = [...]`, `desired_count`, `task_size` per service, `loadtest_allow_cidr` (runtime only, D6),
`capacity = "FARGATE_SPOT" | "FARGATE"`, `waf_baseline_mode = "count" | "block"`.

---

## 3. Decisions (settled 2026-11-11; D20 deferred)

Each entry gives the decision, the reason and the cost. **Architect review 2026-11-11:** every
entry was reviewed against current industry practice before it was settled. The *Review* lines
record what that review changed and why, and stay as the reasoning.

| ID | Decision | Built in slice |
|---|---|---|
| D1 | Hybrid modules: community VPC, own modules for the domain | 0–1 |
| D2 | Terraform ≥ 1.11, gates, `terraform test`, Dependabot, SSM between stacks | 0 |
| D3 | Own VPC, 3 AZs, `/20` app subnets, per-service SGs, endpoint policies, flow logs | 1 |
| D4 | Zonal NAT for sessions; regional NAT as the documented production mode | 2 |
| D5 | REST API → internal ALB via VPC Link v2; integration in `session` | 2 |
| D6 | WAF on queue join **and** purchase; stacked keys; baseline; logging | 2, 5 |
| D7 | Fargate ARM64, Spot in dev, readiness checks, scheduled + target-tracking scaling | 2, 5 |
| D8 | ElastiCache, Valkey engine, TLS | 3 |
| D9 | Private RDS, one database role per service | 3 |
| D10 | Migrations as a one-off ECS task | 3 |
| D11 | One CMK, with a written rule for which stores use it | 1 |
| D12 | IAM as code; one execution role per service; humans read, pipelines write; workload in a member account | 0–1 |
| D13 | Secrets: write-only, rotated per session; third-party keys set out of band | 3 |
| D14 | Four OIDC roles, protected `main`, nothing identifying in public logs | 0, 4 |
| D15 | Workflows: affected images, digests in SSM, reaper with `expires_at`, drift | 4 |
| D16 | Alarms that need a human, `treat_missing_data` pinned, invariants every session | 1, 5 |
| D17 | Cost guards, fastest first; Infracost | 0, 4 |
| D18 | WebSocket connect tickets: authentication and the per-token cap | 6 |
| D19 | Ticket verification and an atomic check-in at the gate | 6 |
| D20 | Custom domain — **deferred**; gaps accepted and listed | — |
| D21 | Phase 3 seams: IAM trust as input, `TargetGroupBinding`, EKS subnet tags | 1, 7 |
| D22 | Lambdas outside the VPC | 1 |
| D23 | Terraform deploys the services, in stages | 3–4 |
| D24 | CloudTrail, security alerts, data protection | 0–1 |
| D25 | Naming: `twr-<env>-…`, computed unique names | 0 |
| D26 | ADRs for the eight significant decisions | 0–7 |
| D27 | GuardDuty on; Config, Security Hub, Control Tower, mesh, multi-region out | 0 |

### Tooling and structure

**D1. Module strategy: hybrid.** *Settled 2026-11-11.*
- **What goes where.** The network plumbing comes from the community
  `terraform-aws-modules/vpc` module. Everything that carries domain meaning is in our own
  modules: the seat table, saga, WAF, ECS services, WebSocket integrations and IAM.
- **Why.** Most serious teams work this way: community modules for commodity infrastructure,
  own modules where the defaults *are* the design. Nearly all of the retro's §3
  pinned-defaults list is in the domain resources, so those stay visible in our code. Phase 3
  adds `terraform-aws-modules/eks` on the same logic.
- *Review: why only the VPC module.* The community `alb`, `ecs`, `rds` and `sqs` modules exist
  and are widely used. They are not used here because each hides tens of inputs behind its own
  defaults, which is exactly what retro §3 says to pin by hand. The VPC module is the one where
  the community defaults are already right and the code is pure boilerplate.
- **Module conventions** (what reviewers in any Terraform shop look for):
  - Files: `main.tf`, `variables.tf`, `outputs.tf`, `versions.tf`, `README.md`.
  - `versions.tf` declares `required_providers` and **never a `provider` block**. Providers are
    configured only in the roots, so a module stays reusable, and Phase 3 can reuse it from the
    EKS stack.
  - Every variable has a `description`, a `type` and, where a wrong value would be silent, a
    `validation` block. Example: TTL attribute names, or a visibility timeout below six times
    the function timeout.
  - Roots in `live/*` are **thin compositions**: wiring between modules, no resources of their
    own beyond SSM outputs.
  - At most one level of nesting: `network` wraps the community `vpc`, and no module wraps a
    module that wraps a module.
  - The README is generated by **`terraform-docs`** and checked in CI, so input and output
    tables cannot drift.
- **Pinning.** Community modules are pinned to an **exact** version (`version = "6.x.y"`), not
  a range. A module upgrade is a reviewed PR with its own plan, never a side effect of `init`.

**D2. Toolchain, gates and module tests.** *Settled 2026-11-11.*
- **Terraform, not OpenTofu.** Most teams and job postings still use Terraform. The BSL licence
  only restricts building a competing product, not using it. OpenTofu gets a sentence in the
  retro.
- **Versions, pinned twice.**
  - `.terraform-version` sets the binary for tfenv/mise and `hashicorp/setup-terraform` in CI.
  - `required_version = "~> 1.11"` in every root and module enforces it. 1.11 is the minimum for
    write-only arguments (D13); use the latest 1.x at slice 0.
  - The AWS provider is `~> 6.x`, with `.terraform.lock.hcl` committed for all three CI
    platforms (`terraform providers lock -platform=linux_amd64 -platform=linux_arm64
    -platform=darwin_arm64`). A lock file missing the runner's platform is a classic first-CI
    failure.
  - tflint, trivy and terraform-docs are pinned to exact versions in the workflow.
- **Tags.**
  - `default_tags { project, phase, stack, env }` on the provider.
  - ECS services set `propagate_tags = "SERVICE"`, because tasks launched by a service do not
    inherit provider tags. That is one of the gaps behind the retro §5 sweep missing 15
    resources.
- **Gates: local hooks are fast, CI is the authority.** Hooks can be bypassed and run on
  someone's laptop; the merge rule cannot.

  | Check | `.githooks/pre-commit` (seconds) | CI, required to merge |
  |---|---|---|
  | gitleaks (existing, extended to `infra/`) | ✓ | ✓ |
  | `terraform fmt -check` | ✓ | ✓ |
  | `terraform validate`, `tflint` (AWS ruleset) | | ✓ |
  | `trivy config` (IaC misconfiguration; tfsec's successor) — *in `ci.yml` since 2026-11-15, image pinned by digest; accepted findings in `infra/.trivyignore.yaml`, each citing its D-entry* | | ✓ |
  | `terraform test` (below), terraform-docs check | | ✓ |
  | `plan` posted to the PR | | ✓ |

  Stay on the existing plain `.githooks` script rather than adding the `pre-commit` Python
  framework: one hook mechanism per repo.
- **Module tests.** `terraform test` with `mock_provider`, plan-only, needing no AWS and no
  money. It covers the modules whose pinned defaults are the design, and asserts things like
  `ttl.enabled`, `raw_message_delivery`, `function_response_types` and the KMS producer grants
  (D11). That turns the retro's §3 table into checks that run on every PR. A mocked plan cannot
  show that AWS *accepts* a value; the session `apply → smoke` run (D15) covers that.
- **Dependency updates: Dependabot.** It is configured for `terraform` (providers and the
  community module), `github-actions`, `gradle` and `docker` (the `eclipse-temurin` base
  images). It runs weekly and groups minor and patch updates into one PR per ecosystem. Pinning
  without an update bot means versions are pinned and then forgotten.
- **Cross-stack values: SSM parameters** under `/<prefix>/<env>/foundation/*` (`/twr/dev/…`, D25), read by `session`
  with data sources.
  - Why not `terraform_remote_state`: the stacks stay decoupled, nobody needs read access to
    another stack's whole state (which contains secrets), and the Phase 3 EKS stack reads the
    same parameters.
  - Accepted trade-off: a parameter is not versioned with the state that wrote it. Foundation
    owns the writes, so there is one writer.
- **State layout.** One state file per root, keyed `<env>/<stack>.tfstate`, in the bootstrap
  bucket. Versioned, SSE, public access blocked, `use_lockfile = true`.
- **Not chosen, with the reason:**
  - **Terragrunt.** It solves keeping many environments and accounts DRY; this project has one
    of each, and it would put a second tool between the reader and the HCL.
  - **HCP Terraform / Spacelift.** Good managed runners and policy, but GitHub Actions with
    OIDC and an S3 backend does the same job here at $0, and it is what most teams on GitHub
    self-host anyway.
  - **Terraform Stacks.** Still tied to HCP.
  - **Policy-as-code (OPA/Sentinel).** trivy plus the IAM permissions boundary (D14) cover
    the policies this account needs.

### Network and edge

**D3. Own VPC across 3 AZs, three subnet tiers, sized for Phase 3.** *Settled 2026-11-11.*
- **Base.** CIDR `10.20.0.0/16`. Subnets in **all three** eu-central-1 AZs: public, app and
  isolated db tiers.
- *Review: three AZs, not two.*
  - Subnets cost nothing. Two AZs is only the *minimum* the ALB and RDS subnet group accept;
    three is what production VPCs use.
  - It means "Multi-AZ RDS" and "per-AZ NAT" stay switches rather than a re-subnetting.
  - The single-instance stores (RDS, Valkey) still live in one AZ (D8, D9).
  - The extra cross-AZ traffic at test volume is cents. Still, the cache AZ is pinned
    (`preferred_cache_cluster_azs`, retro §3) next to the NAT AZ.
- *Review: subnet sizing.* `/24` app subnets were the first draft's mistake.
  - Fargate takes one IP per task, but **Phase 3's EKS VPC CNI takes one IP per pod**, and
    small private subnets are the most common reason an EKS migration needs a new VPC.
  - The industry pattern is large private subnets, small public ones and small data ones.

  | Tier | AZ a | AZ b | AZ c | Holds |
  |---|---|---|---|---|
  | public `/24` | `10.20.0.0` | `10.20.1.0` | `10.20.2.0` | NAT Gateway only (no ALB: it is internal, D5) |
  | db `/24` | `10.20.4.0` | `10.20.5.0` | `10.20.6.0` | RDS, Valkey; **no route out** |
  | app `/20` | `10.20.16.0` | `10.20.32.0` | `10.20.48.0` | ALB nodes, VPC link ENIs, tasks, Phase 3 nodes and pods |
  | reserved | `10.20.64.0/18` and up | | | Phase 3 (e.g. a dedicated pod range), never allocated by Phase 2 |

  `10.20/16` avoids the default `10.0/16` and the common home/VPN ranges, so a later VPN or
  peering does not collide.
- **Gateway endpoints** (free) live in foundation for S3 and DynamoDB, so ECR layer pulls, QR
  writes and every seat write skip the NAT.
  - *Review:* attach **endpoint policies**. The S3 policy allows the project buckets plus ECR's
    regional layer bucket (`prod-eu-central-1-starport-layer-bucket`); without that bucket every
    image pull breaks, which is the classic way this goes wrong. The DynamoDB policy allows the
    project tables.
  - This is the standard data-exfiltration control: a compromised task cannot copy data into
    someone else's bucket through our endpoint.
- *Review: security groups per service, not one shared "tasks" SG.*
  - With one SG for all tasks, `notification-service` could reach Postgres. Per-service SGs
    make the network say what the IAM roles say:

  ```
  vpclink-sg → alb-sg → {waiting-room, seat-inventory, checkout, issuance}-sg
  waiting-room-sg → cache-sg        checkout-sg, issuance-sg, migrate-sg → db-sg
  notification-sg → (egress only)
  ```

  - The VPC's **default SG is emptied** (`manage_default_security_group`, no rules), which is a
    trivy finding otherwise.
  - Network ACLs stay at the default allow-all. Industry practice keeps filtering in SGs, which
    are stateful and referenceable, and reserves NACLs for coarse subnet-level denies this VPC
    does not need.
- **VPC Flow Logs to S3** (not CloudWatch: vended-log ingestion to S3 is the cheap path), with a
  7-day lifecycle. They are part of every production baseline, and they are how slice 2 proves
  the claim that ECR and DynamoDB traffic skips the NAT. With no traffic outside sessions, the
  cost is about $0.
- *Not chosen: IPv6 / dual-stack.* An egress-only internet gateway is free and would remove the
  NAT for IPv6 destinations. But the task bootstrap path (ECR auth, CloudWatch Logs, Secrets
  Manager, SSM, Cognito JWKS) does not have IPv6 endpoints for every service, so IPv4 egress and
  the NAT stay. Dual-stack would add a second addressing plan for no cost saving. Revisit when
  every dependency is dual-stack.
- *Cost:* about $0 standing.

**D4. Egress: one zonal NAT Gateway, session-only, in AZ a.** *Settled 2026-11-11.*
- **What actually needs it.** Nothing in a task starts without egress:
  - ECR auth and manifests (layers go via the S3 endpoint);
  - CloudWatch Logs (the `awslogs` driver), Secrets Manager and SSM (task startup);
  - SQS, SNS and SES;
  - Cognito's JWKS;
  - the collector's OTLP export to X-Ray and CloudWatch;
  - ECS Exec (`ssmmessages`).

  So for a session the choice is *which* egress, not whether.
- *Review: a new option exists.* AWS shipped **regional NAT Gateway** (Nov 2025): one gateway
  that follows workloads across AZs, needs no public subnet and no per-AZ route tables, and is
  AWS's recommendation for most new VPCs. It is the right **production** answer here: HA egress
  without managing three NATs. It is **not the right session answer**, for two reasons:
  - it bills for each AZ it has expanded into, which is about 3× a single zonal NAT once tasks
    spread over three AZs;
  - expansion into a newly used AZ can take up to an hour, which is longer than a session.
- **Decision:**
  - a *zonal* NAT in AZ a for sessions, with the other AZs routing to it across AZs. The
    accepted trade-off: an AZ-a outage takes egress down;
  - `nat_mode = "zonal" | "regional"` as a variable, so the production shape is one switch,
    and documented;
  - confirm Terraform provider support for regional mode in the slice 0 checks.
- *Not chosen:*
  - interface endpoints (about $8/month each, 8–10 needed: more than NAT at this scale, retro
    §6.1);
  - fck-nat on `t4g.nano` (about $0.005/h). Common in cost-driven startups, but it is an
    instance to patch, and a serious team picks the managed gateway unless NAT data charges
    dominate the bill. Here the gateway endpoints remove the heavy traffic first.
- *Cost:* about $0.057/h including the EIP, plus $0.052/GB.

**D5. REST API → internal ALB through VPC Link v2.** *Settled 2026-11-11.*
- **The change.** AWS shipped REST API private integration with ALB in Nov 2025. The ALB
  becomes **internal**, its SG admits only the VPC link's SG, and `X-Edge-Secret` is deleted
  instead of moved to a secret store. This is the strongest of the three edge options: the
  backend is unreachable except through the stage that carries WAF.
- *Review: keep REST, and keep it `REGIONAL`.*
  - The REST API stays because it does three things nothing simpler does together: WAF
    attachment, the `StartExecution` service integration with no Lambda, and request mapping.
    The **CloudFront + VPC origin → internal ALB** pattern is the other industry answer for "WAF
    in front of a private ALB", but it cannot start the saga. It becomes relevant when a
    frontend arrives (CloudFront is in the not-built list).
  - The endpoint type is pinned to `REGIONAL`. Edge-optimised puts an AWS-owned CloudFront
    distribution in front that we cannot attach our WAF to, and adds nothing for a
    single-region API.
- *Review: the resources and the stack split, now settled by evidence.*
  - The private integration is `aws_api_gateway_integration` with `connection_type =
    "VPC_LINK"`, `connection_id` = a **v2** link (`aws_apigatewayv2_vpc_link`, the same
    resource HTTP APIs use), and the ALB as the integration target. The classic
    `aws_api_gateway_vpc_link` is v1 and NLB-only, and using it by mistake is the common trap.
  - AWS re:Post reports that a **stage variable is rejected as the ALB ARN**. So the integration
    holds the real ARN, which is a session value. By the retro's rule, the `/{proxy+}`
    integration, the deployment and the stage go in `session`. The REST API and its
    resources/methods stay in foundation, so the API id and the invoke URL never change.
  - Spike in slice 2: confirm the provider argument name for the target (the CLI calls it
    `--integration-target`; AWS's Serverless Land pattern deploys it with Terraform) and
    eu-central-1 availability.
  - *Spike result 2026-11-17: confirmed.* The argument is `integration_target` (AWS provider
    6.73), eu-central-1 accepts it, and a request through the public `dev` stage returns the
    internal ALB's own `404 {"error":"NO_ROUTE"}` in 5 ms of integration latency. The fallback
    is not needed. Note that a fresh stage answers 500 for a minute or two (`integrationStatus`
    `-`, the request never sent) before it picks up the new link
    (`runbook-session-edge.md` §3).
- *Review: the saga's calls hairpin through the public stage.*
  - Step Functions calls the services through `/{proxy+}` with its M2M token, so they pass the
    WAF too. That is acceptable and even good: one path, one WAF, one JWT check.
  - It does mean the D6 baseline rules (count mode first) must be reviewed **against the saga's
    own requests** before switching to block. A false positive there turns every purchase into
    a compensation.
  - The private alternative is EventBridge connections to a VPC Lattice resource configuration,
    which keeps the saga's traffic inside the VPC. It adds Lattice charges and a second path to
    reason about. Documented, not built.
- *Review: HTTP between the VPC link and the ALB.*
  - The hop is inside the private VPC, and the ALB's SG admits only the VPC link's SG. Plain
    HTTP there is standard outside regulated workloads.
  - TLS would need a certificate. A public ACM certificate needs a domain, which is deferred
    (D20), and a private CA costs about $400/month.
  - HTTPS end to end is the first change if D20 comes back.
- *Fallback, if the spike fails.* An internet-facing ALB *plus* the edge header in SSM
  SecureString. To be honest about it, restricting the ALB's SG to API Gateway's address ranges
  is **not** a security control: those ranges are shared with every AWS customer's API Gateway.
  The secret header is what protects that design, which is why the private ALB is worth the
  spike.
- *Cost:* ALB as before, minus its public IPv4 addresses (about $0.01/h saved). No separate
  VPC link charge has been found; re-check this when re-pricing in slice 0.

**D6. WAF: protect the queue entry as well as the purchase, and stop trusting the IP alone.** *Settled 2026-11-11.*
- **Carried over.** Phase 1's rules: a rate limit of 100/min per IP plus Bot Control (common
  level), scoped to `POST /events/{id}/purchase` and proven enforced (`runbook-waf.md` §7). WAF
  here is a domain requirement, not generic hardening.
- *Review: the scope is one door short.* Scalpers do not flood the purchase endpoint first;
  they flood the **queue** (`POST /events/{id}/queue`). A thousand bot accounts holding a
  thousand queue positions is how the fair FIFO this project proves gets defeated without
  breaking any invariant. So the buyer-facing rules are scoped to **both** entry points: queue
  join and purchase. The rest of `/{proxy+}` gets only the baseline below.
- *Review: per-IP alone is the weakest key.* A per-IP rate rule is both too strict and too
  loose. Mobile carriers' CGNAT and a venue's Wi-Fi put hundreds of real buyers behind one IP,
  while a scalper rents a residential-proxy pool and never repeats an IP. Current practice
  stacks keys, each catching a different shape:

  | Rule (buyer entry points only) | Key | Catches |
  |---|---|---|
  | flood guard | IP, a generous limit | a single host hammering |
  | **fleet guard** | **JA4 TLS fingerprint** (WAF native since Mar 2025) | one bot toolkit rotating through many IPs |
  | credential guard | `Authorization` header | one token used faster than a human can |
  | Bot Control, common | managed | HTTP libraries, known automation |
  | `AWSManagedRulesAnonymousIpList` | managed | requests from hosting providers and VPNs, where bot fleets run |

  - The real *per-account* limit (tickets per buyer) stays in the application, where the
    account is known. WAF cannot read a JWT's claims.
  - **Spike in slice 2:** confirm WAF computes JA4 for an ACL attached to a REST API stage. The
    announcement is written around CloudFront and ALB. If it does not, the fleet guard waits for
    CloudFront (not built) and the other four still stand.
  - *Spike result 2026-11-17: not computed.* With a JA4-keyed rate rule on the REST stage's
    ACL, all 46 logged requests from two different TLS clients (curl and Python) carry **no**
    `ja4Fingerprint` field, and no `ja3Fingerprint` either. **Changed 2026-11-17: the fleet
    guard is dropped from the REST-stage ACL** and waits for CloudFront (D5's documented
    alternative, not built). The flood guard, credential guard, Bot Control and the
    anonymous-IP list still stand, so the buyer rules are four, and the cost estimate's rule
    count drops by one. The same logs confirmed `Authorization` arrives as `REDACTED`.
  - The anonymous-IP rule must **never** cover the paths the saga calls. Step Functions comes
    from AWS addresses, which is exactly what that list blocks. The scope-down is the guarantee.
- **Baseline on the whole stage.** A real edge always carries the AWS managed baseline:
  `AWSManagedRulesCommonRuleSet`, `KnownBadInputsRuleSet` and `AmazonIpReputationList`.
  - Start them in **count** mode for one session and review the sampled requests, including the
    saga's own requests (D5). The common rule set's body-size rules are known to flag JSON APIs.
  - Then set them to block, or add scoped exclusions.
- *Review: API Gateway throttling as the free backstop.* Method-level throttling (rate and burst)
  on the two entry points caps what reaches the backend even if a WAF rule is misconfigured.
  It is free and needs no code. WAF decides *who*; throttling bounds *how much*.
  - *Added 2026-11-17, found implementing it:* a method setting can only target a method
    that is defined, and queue join had none of its own: it went through `ANY /{proxy+}`.
    Foundation now declares `/events/{eventId}/queue` with `POST` and `GET`. `GET` is the
    position poll on the same path, declared because a request that matches a resource but
    not one of its methods is refused there instead of passed on to `/{proxy+}`. The session
    stack gives both the VPC-link integration. Only the two `POST`s are throttled, never
    `*/*`, which would throttle the saga too. The defaults are 500 rps / burst 1000 for queue
    join and 100 / 200 for purchase. The same resource paths, published in SSM, build the WAF
    buyer-entry regex, so the throttled and the WAF-scoped paths cannot drift apart.
- *Review: WAF logging.* Logs go to S3 (the bucket must be named `aws-waf-logs-*`), with the
  `Authorization` header **redacted** in the logging configuration and a 7-day lifecycle. Without
  logs, a block cannot be explained after the fact, and every serious edge keeps them. The
  sampled requests in the console are a 3-hour window, not a record.
  - *Changed 2026-11-17: the bucket is foundation's,* `aws-waf-logs-twr-<env>-<account-id>`
    (`live/foundation/edge.tf`, the `s3` module, SSE-S3 like the flow logs). The ACL is
    session-only, and a bucket destroyed with it would take the evidence with it. Its name
    is the one exception to the `twr-` prefix (D25), because WAF delivers only to
    `aws-waf-logs-*`. Session reads it from SSM (`waf/log_bucket_arn`, with `waf/acl_name`).
- *Review: the load test and the bot rules cannot be the same run, and the plan should say so.*
  k6 from one machine looks like what Bot Control and the flood guard exist to stop. Phase 1
  hit this, and the retro asked for "a run with Bot Control enforced". The honest design is two
  proofs:
  1. a **WAF proof**: targeted requests show each rule blocking with its own label;
  2. the **concurrency proof**: the load test, with an **IP-set allow rule** evaluated before
     the buyer rules. The runner's address is passed in at apply time as a variable, never
     committed (CLAUDE.md *Secrets*), and the rule is removed in the same session.

  Claiming one run proves both would be wrong about one of them.
- *Future, documented: Challenge/CAPTCHA at the queue.* Real ticketing platforms put an
  interactive challenge at queue entry: Bot Control's *targeted* level plus WAF's
  Challenge/CAPTCHA actions, backed by the JavaScript integration SDK's token. All of that
  needs a browser frontend, which does not exist yet. Write it down as the first edge change
  when a frontend lands.
- *Cost:* web ACL $5 plus about 9 rules × $1 plus Bot Control $10, ≈ **$24/month prorated
  hourly**, plus $1 per million requests Bot Control analyses (kept low by the scope-down).
  The ACL exists only during sessions, so this is ≈ $0.033/h.

### Compute and data

**D7. ECS Fargate on ARM64, Spot in dev, scaled ahead of the door opening.** *Settled 2026-11-11.*
- **Architecture.** ARM64 (Graviton) costs about 20% less per task-hour and is the default
  for new JVM services; `eclipse-temurin` publishes arm64 images. The Lambdas move to `arm64`
  too.
- **Capacity: Spot on Graviton is supported** (since Sep 2024), and the two discounts stack.
  - Dev sessions run **FARGATE_SPOT** only.
  - The proof run and the documented production setting use the standard mixed strategy:
    `FARGATE` with `base = 1` (one task that never gets interrupted) and `FARGATE_SPOT`
    carrying the weight above it.
  - A Spot interruption is a SIGTERM with two minutes' notice. Graceful shutdown (Boot's
    default since 3.4) plus delete-after-handler on the queues makes that safe by
    construction. Slice 5 should see one happen.
- **Deployments.**
  - `deployment_circuit_breaker { enable = true, rollback = true }`, with
    `wait_for_steady_state = true` (retro §3).
  - `minimum_healthy_percent = 100`, `maximum_percent = 200`.
  - *Review:* set the target group `deregistration_delay` to **30 s**. The default is 300 s,
    which makes every deploy and every scale-in wait five minutes, and every session pays for
    those minutes.
  - The shutdown order is: the ALB drains the target (`deregistration_delay`), then ECS sends
    SIGTERM and waits `stopTimeout` before SIGKILL. So the container's **`stopTimeout` (set to
    45 s) must exceed Boot's `spring.lifecycle.timeout-per-shutdown-phase` (30 s)**, or ECS kills
    in-flight requests mid-shutdown. The ECS default of 30 s equals Boot's default, which is the
    silent version of that race.
- *Review: health checks that mean something.*
  - The ALB checks `/actuator/health/readiness`, not plain `/actuator/health`.
  - That needs `management.endpoint.health.probes.enabled=true`, because Boot only enables the
    probe groups by itself on Kubernetes. It is a one-line change in `observability-starter`.
    `security-starter` already permits `GET /actuator/health/**` anonymously, so the readiness
    path needs no security change (checked against `TicketingSecurityAutoConfiguration`).
  - A task that is draining or still warming its JWKS cache then stops receiving traffic,
    instead of failing requests while "healthy". This is also the exact contract Phase 3's
    Kubernetes probes use, so it is written once.
  - *Changed 2026-11-17, found implementing it.* Two premises above were wrong.
    - **Boot 4 enables the probes by default.** `probes.enabled` defaults to `true` in Boot
      4.1's metadata, so the line was already on. `observability-starter` now pins it anyway,
      with graceful shutdown and the 30 s phase, through a lowest-precedence
      `EnvironmentPostProcessor` (`ObservabilityDefaults`). The ALB path and `stopTimeout`
      are set from these values, so a changed Boot default must not move them silently.
    - **Readiness did not wait for the JWKS warm-up.** `JwtDecoderWarmUp` ran *after*
      `ApplicationReadyEvent` on a virtual thread, and Boot publishes `ACCEPTING_TRAFFIC` at
      that moment, so readiness went green before the warm-up began. It is now an
      `ApplicationRunner`, which Boot completes before it reports ready. The runner waits on
      the virtual thread for at most 30 s, because Nimbus's default client has no read
      timeout. It still never fails startup: past 30 s, or with the pool unreachable, the
      task reports ready and the first real token pays, as before. Proven by
      `JwtDecoderWarmUpTest.bootReportsReadyOnlyAfterTheWarmUp`, a real `SpringApplication`.
  - `health_check_grace_period_seconds` comes from the measured startup, not a guess.
- *Review: task size per service.*
  - 0.25 vCPU / 0.5 GB was Phase 1's measured weak point: a JWKS warm-up of 2.7–13.6 s, and the
    losers' p99 queued behind one task.
  - Feature slices keep 0.25 / 0.5. The proof runs **0.5 vCPU / 1 GB × 2 tasks across AZs**, so
    the numbers show the system rather than CPU starvation.
  - *Changed 2026-11-18, found writing `modules/ecs-service`.* Feature slices run **0.25 vCPU /
    1 GB**, not 0.5 GB. The task also carries the collector sidecar, which keeps 256 MB, and
    the JVM sizes its heap from what is left (`MaxRAMPercentage` against the container's hard
    limit). Phase 1 moved to 1 GB for exactly this when it added the sidecar
    (`runbook-observability.md`). Cost: about $0.002/h more per task.
- *Review: scale ahead, then track.* An on-sale is a **scheduled** spike. Fargate needs about a
  minute to place a task and the JVM needs more to warm, so reactive scaling alone arrives after
  the door has already opened. Ticketing platforms pre-scale for a known on-sale time. Use both:
  - **scheduled scaling**: an `aws_appautoscaling_scheduled_action` raises `min_capacity` a few
    minutes before the event's sale time;
  - **target tracking** for what the schedule did not predict: ALB `RequestCountPerTarget` for
    the HTTP services, and **backlog per task** (queue depth ÷ running tasks, a metric-math
    target as AWS recommends) for the SQS consumers.
  - `max_capacity` = 4.
  - The waiting room is the design's real load control. Admission rate bounds what reaches
    seat-inventory and checkout, so those scale for the admitted rate, not the crowd.
- *Review: faster JVM start with the JDK's AOT cache.* JDK 25+ (Project Leyden, JEP 514/515)
  can record a training run into an AOT cache that the container loads at start-up, and Boot 4
  supports it. Building the cache in the Dockerfile is the modern answer to "scale-out is too
  slow", and it costs nothing at runtime.
  - Spike in slice 5: measure start-up with and without it.
  - Adopt it only if the gain is real on 0.5 vCPU. The Lambdas' INIT (retro §9) is the same
    lever, worth the same measurement.
- *Review: container hardening.* These are the trivy defaults, and a reviewer checks them first:
  - a non-root user (already done: `USER app`);
  - `readonlyRootFilesystem = true`, with a small writable `/tmp` volume for Tomcat;
  - `linuxParameters { capabilities { drop = ["ALL"] } }`;
  - no privileged mode.
  - *Added 2026-11-18, found writing `modules/ecs-service`.* A read-only root conflicts with
    ECS Exec (D9). AWS documents ECS Exec as unsupported on a read-only root filesystem,
    because its SSM agent writes into the container. AWS re:Post's workaround is used: two more
    writable task-storage mounts, at `/var/lib/amazon/ssm` and `/var/log/amazon/ssm`.
    - It is unsupported, so the first session proves it with a real `execute-command`.
    - If it fails, Exec wins and the application container's root goes writable. The bastion
      is how `psql` reaches the private database; the hardening is a reviewer's default.
  - *Found 2026-11-18, first apply.* Fargate creates a task-storage mount root-owned `0755`,
    so the non-root `app` user could not write `/tmp` and Tomcat did not start. The image
    now declares `VOLUME ["/tmp"]`, and Fargate seeds the mount from the image's `/tmp`
    (`1777`). All five service Dockerfiles carry the line: seat-inventory's on the first apply, the other four the same day, each image built locally and read back (`/tmp` a volume, `1777`, user `app`).
- *Review: logging.* Keep the `awslogs` driver, with `mode = "non-blocking"` and a
  `max-buffer-size` pinned explicitly. In blocking mode, a CloudWatch hiccup stalls the
  application's own threads, which during a door-open looks like a concurrency bug that is not
  one.
- **Access.** **ECS Exec** is enabled for operator access (D9).
- *Not chosen:*
  - **Service Connect / a mesh.** There are no east-west calls; the saga goes through the edge.
  - **Container Insights.** Billed per metric, and it duplicates the services' own meters plus
    ECS's free service metrics.
  - **EC2 capacity providers.** Fargate is the point of Phase 2; nodes arrive with EKS.

**D8. ElastiCache with the Redis-compatible Valkey engine on `cache.t4g.micro`, TLS in
transit.** *Settled 2026-11-11.*
- **Redis is not dropped; only the engine changes.** Valkey is the Linux Foundation fork of
  Redis OSS 7.2.4, created after Redis's 2024 licence change, with the same wire protocol,
  commands and data structures.
- **Nothing in the queue design changes.** The `INCR`-scored sorted set, `ZPOPMIN` admission and
  the `join`/`position`/`admit` Lua scripts run as they are. Spring Data Redis / Lettuce connect
  with no dependency change. The docs keep calling the mechanism a *Redis sorted set*.
- **Why Valkey.** It is the engine AWS now leads with on ElastiCache: priced about 20% below
  Redis OSS nodes, actively developed, and BSD-licensed. Redis OSS on ElastiCache stays on the
  7.x line.
- Going back is one line (`engine = "redis"`).
- **TLS in transit** is a small config change in `waiting-room-service`
  (`spring.data.redis.ssl.enabled`).
- **Resource:** `aws_elasticache_replication_group` with one node. Retro §2 asks for a
  deliberate choice; this is the shape that supports TLS and RBAC users.
- *Review: authentication.* Phase 2 runs **TLS plus SG isolation**: only `waiting-room-sg`
  reaches `cache-sg` (D3). An RBAC user with a password (a fifth secret) or IAM authentication
  is the production setting, because a queue token is a bearer value. It is documented as the
  next step, not built. This is stated rather than implied by the word "RBAC".
- *Alternative:* ElastiCache Serverless Valkey. No node to size, but a standing minimum; not
  worth it here.
- *Cost:* about $0.014/h.

**D9. RDS Postgres private, one database user per service.** *Settled 2026-11-11.*
- **Instance.** `publicly_accessible = false`, isolated subnets, `gp3`, `storage_encrypted` with
  the CMK (D11), `manage_master_user_password = true`, `skip_final_snapshot = true` (settled in
  the retro).
- **Settings.** `multi_az` is a variable, default `false`; the production value is documented.
  Postgres major version pinned and `auto_minor_version_upgrade = true`.
- **Human access.** `psql` goes through **ECS Exec into a task**, or a `run-task` with `psql`.
  This replaces the `$MYIP` SG rule.
- *Review:* Phase 1 had **both services log in as the RDS master user**, and the `ticketing`
  database was created by hand (`CREATE DATABASE ticketing`, `runbook-ticket-issuance.md`). A
  shared superuser across two bounded contexts undermines the "separate bounded context, no FK"
  claim: `checkout-payment-service` could read or drop the tickets table. Phase 2 changes this:
  - The master user becomes **migration-only** (D10). It owns the schema, so DDL is never
    available to a running service.
  - Each service gets its **own login role** with **DML only on its own database**:
    `checkout_app` and `ticketing_app`.
    - After Flyway: `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES` and `USAGE` on
      sequences, plus `ALTER DEFAULT PRIVILEGES`, so the next migration's tables are covered too.
      Without the grants the service cannot read its own tables.
    - **`REVOKE CONNECT ON DATABASE … FROM PUBLIC`** on both databases. Postgres grants
      `CONNECT` to everyone by default, so without it `checkout_app` could open the `ticketing`
      database, and the separation would exist only on paper.
  - Credentials are handled per D13.
- *Not chosen:* RDS Proxy (a 2-vCPU minimum charge, and only two small pools to protect) and IAM
  database authentication (a 15-minute token behind a Hikari pool is real code for no gain at
  this scale). Both are documented as the next step if the task count grows.
- *Cost:* about $0.018/h.

**D10. Migrations as a deploy step.** *Settled 2026-11-11.*
- **Code work this implies (slice 3, before the RDS session):**
  - **`migrate` profile** in `checkout-payment-service` and `ticket-issuance-service`. It turns
    the web server off and Flyway on. A runner creates the database and the service role if
    missing, runs Flyway, and exits 0 or 1.
  - **Role creation** runs as the master user. It is idempotent and reads the service's password
    from its secret.
  - **Normal startup** uses the service's own role, with `spring.flyway.enabled=false` (in the
    task definition, not the default `application.yml`), so local `bootRun` keeps migrating as it
    does today.
  - **Proof test:** a Testcontainers test running the `migrate` profile twice against a fresh
    Postgres. The second run is a no-op. Then the app role:
    - can read and write its own tables;
    - is **refused DDL** (`DROP TABLE` fails);
    - is **refused `CONNECT`** to the other database.
- **Mechanism.** A one-off ECS task per database (`checkout`, `ticketing`). It uses the *same
  image* with a `migrate` Spring profile: Flyway on, web server off, exit code 0 or 1.
- **Order.** CI runs it with `aws ecs run-task --wait` **before** updating the service. Normal
  startup has Flyway disabled.
- **Why.** This settles retro §6.6. Services can scale to N tasks without racing migrations, and
  a failed migration stops the deploy instead of crash-looping tasks.
- *Review:* the migration task is the one place that runs as the master user. It creates the
  database and the service role idempotently (`CREATE DATABASE` cannot run inside Flyway's
  transaction, so this is a small pre-Flyway step in the `migrate` profile), then runs Flyway.
  That removes the last hand-run SQL from a rebuild. Keep `spring.flyway.validate-on-migrate`
  on, so an edited migration fails loudly.
- *Cost:* seconds of Fargate.

### Security

**D11. One customer-managed KMS key, with a written rule for what uses it.** *Settled 2026-11-11.*
- *Review: the first draft listed services; it needs a rule.* A reviewer's question is "why
  this store and not that one", so the criterion is stated once:
  **a store holding customer data or credentials uses the CMK; everything else uses
  AWS-owned encryption, and says why.**

  | Store | Key | Why |
  |---|---|---|
  | RDS (orders, payments, buyer email) | CMK | personal and payment data |
  | S3 QR bucket | CMK | a QR **is** a ticket, a bearer credential |
  | SQS queues, SNS topic | CMK | `purchase-confirmed` carries the buyer's email |
  | Secrets Manager (per-service DB secrets, RDS master secret) | CMK | credentials; the key policy becomes a second gate on who can decrypt them |
  | ElastiCache Valkey (the waiting-room queue) | CMK | *added 2026-11-19, writing `modules/cache`:* the queue holds queue tokens, bearer values (D8), so the rule puts it here. It covers the node's disk and swap; snapshots are off |
  | Step Functions: the checkout saga's definition, execution input and history | CMK | *added 2026-11-19, writing the state machine:* the input carries the buyer's email (`purchase-confirmed`), and the history keeps it for 90 days. That is customer data, and unlike the connection's secret there is no second copy outside the key. The saga role holds `GenerateDataKey`/`Decrypt` scoped by the `aws:states:stateMachineArn` encryption context. There is no `kms:ViaService` on it, because AWS warns that it can stop executions starting. The key policy lets `delivery.logs.amazonaws.com` decrypt for the execution logs. The caller of `StartExecution` (API Gateway's role) needs nothing. The logs themselves carry no execution data |
  | EventBridge connection secret (the saga's ops client secret, `events!connection/…`) | AWS-owned | *added 2026-11-19, writing `modules/saga`:* the same client secret is in state (D13), so a CMK on this copy would gate one copy of a value whose other copy is not behind the key. The controls are who can read state and who can call `events:RetrieveConnectionCredentials` (the saga role only, scoped to `connection/twr-<env>-*`). A CMK would also need the key tag `EventBridgeApiDestinations` and a key-policy statement for no gain |
  | DynamoDB seat and connection tables | AWS-owned | no personal data beyond a pseudonymous `sub`; a CMK would put a key-state dependency on the hottest conditional-write path for no confidentiality gain |
  | CloudWatch Logs | AWS-owned | instead, the rule is **no personal data in logs**: emails are never logged, which is an application rule that is already true and stays true |
  | Terraform state bucket | SSE-S3 + bucket policy | access, not the key, is the control. Only `tf-plan`, the two `tf-apply-*` roles and the owner's SSO role can read it; a second key would cost $1/month to restate that policy |

- **One key, not one per service.** Separate keys earn their $1/month each when different teams
  own different data or when one must be disabled independently. Here one team owns all of it,
  and per-principal statements in the key policy give the same separation. The production split
  would be per bounded context; documented, not built.
- **Key policy shape.** This is the part that goes wrong silently, the same way retro §3 did.
  - Keep the standard *"enable IAM policies"* root statement. Without it, a typo in the key
    policy can make the key unmanageable, which is the classic KMS lockout.
  - Grant use through IAM policies conditioned on `kms:ViaService` (retro §4).
  - Service principals get explicit key-policy statements with `aws:SourceArn`:
    - **SNS → SQS** needs one for `sns.amazonaws.com`, otherwise the message is dropped with no
      error at the publisher;
    - the **Step Functions** role and **EventBridge** need the producer grant
      (`kms:GenerateDataKey` + `kms:Decrypt`), not only `sqs:SendMessage`.
  - One `terraform test` (D2) asserts each producer's grant.
- **Pinned settings.**
  - `enable_key_rotation = true` (annual, automatic).
  - `deletion_window_in_days = 7`.
  - S3 `bucket_key_enabled = true` (Phase 1 had it). It cuts KMS requests by about 99%.
  - SQS `kms_data_key_reuse_period_seconds = 300`, so a load test does not become a KMS
    request bill.
- *Cost:* $1/month standing (rotation adds nothing within the phase), plus pennies of requests.

**D12. IAM as code, least privilege, nothing implied** (retro §4). *Settled 2026-11-11.*
- **Carried.** Every role declared; confused-deputy conditions written out;
  `AWSLambdaBasicExecutionRole` replaced by a policy scoped to the function's own log group;
  the identity-vs-service-role rule as a comment in `modules/iam`; IAM Access Analyzer (the free
  external-access analyzer), whose findings fail slice 1.
- *Review: how policies are written.*
  - Policies come from `aws_iam_policy_document` data sources, never `jsonencode` or heredoc
    JSON, so Terraform validates the structure and diffs are readable.
  - A role's own policy is **inline** (`aws_iam_role_policy`), so its lifecycle is bound to the
    role. A customer-managed policy is used only when two roles genuinely share it.
  - `Resource = "*"` only where the API offers nothing narrower. Each such case gets a comment
    and, where possible, a condition:
    - `ecr:GetAuthorizationToken` and `xray:PutTraceSegments`;
    - `cloudwatch:PutMetricData`, conditioned on `cloudwatch:namespace`.
- *Review: one execution role per service, not a shared one.* The execution role is what fetches
  a task's secrets. A shared one would let checkout's task definition pull ticketing's database
  password. Each execution role is scoped to its own secret ARNs, its own log group and the ECR
  repository it runs.
- *Review: ECS Exec is audited.* `execute_command_configuration` logs every session to CloudWatch
  Logs. A shell into a production task that leaves no record is the kind of access an auditor
  flags first.
- *Review: humans read, pipelines write.*
  - **Foundation** is applied only by `tf-apply-foundation` from CI (D14), **from the end of
    slice 4**, when that pipeline exists. Before then, slices 0–3 apply from the owner's machine
    under the admin permission set. That is the honest bootstrap order: the rule cannot predate
    the pipeline that enforces it.
  - The owner's day-to-day SSO role has read access plus ECS Exec.
  - Admin is a separate SSO permission set used for bootstrap and break-glass.
  - The documented exception: **session** applies may run locally under SSO during development,
    because a 5-minute CI round trip per iteration is not worth it for resources destroyed the
    same evening.
- *Review: the account itself.* Real teams never run workloads in an Organizations
  *management* account, and they separate dev and prod into different accounts.
  *Changed 2026-11-13: the check found a management account (Identity Center had created the
  organization), so the workload moved out before anything was built.*

  | Account | Holds | Applied by |
  |---|---|---|
  | management | Organizations, IAM Identity Center, billing, SCPs, `infra/org/` state. **No workloads, no OIDC roles, no CI access** | the owner, by hand, admin permission set |
  | `twr-dev` (OU `workloads`) | everything in `bootstrap`, `foundation` and `session` | the owner (slices 0–3), then CI (D14) |

  - **Done by hand (2026-11-13):** `twr-dev` created with its own owner email and the default
    `OrganizationAccountAccessRole`; OU `workloads`; centralised **root access management**
    (root credentials management + privileged root actions), so `twr-dev` has **no root
    credentials** at all and a root-only task runs as a short root session from the
    management account; the owner's SSO user assigned `AdministratorAccess` on `twr-dev`;
    local CLI profile `twr-dev` (the account id lives only in `~/.aws/config`).
  - **`infra/org/`**, a small root applied from the management account, makes the rest code:
    - the **SSO permission sets** and their assignments: day-to-day read + ECS Exec, and the
      admin set for bootstrap and break-glass (the console-made assignment is imported);
    - **SCPs on `workloads`** (free; they bind every principal in `twr-dev`, the pipeline roles
      included):
      - deny every region except `eu-central-1` and `us-east-1` (`NotAction` exempts global
        services: IAM, STS, Organizations, Budgets, Cost Explorer, Support);
      - deny `cloudtrail:StopLogging`/`DeleteTrail`/`UpdateTrail` and GuardDuty
        detector deletion or disabling, except for the admin SSO role (`aws:PrincipalArn`
        pattern), so a leaked pipeline credential cannot blind the audit trail;
      - deny `organizations:LeaveOrganization`;
      - deny any action by a member account's root user (defence in depth beside removed root
        credentials), **except sessions started with `sts:AssumeRoot`** (`aws:AssumedRoot`).
        *Found 2026-11-13:* the unconditional deny also blocked the centralised root
        sessions, i.e. the only break-glass path;
    - a **$1 budget filtered to the management account's own spend**: nothing should ever bill
      there, so any spend is an action (D16's rule holds, unlike the retired zero-spend budget
      in `twr-dev`, D17);
    - its own small state bucket in the management account (the bootstrap pattern: local
      state, then migrated). It never shares the `twr-dev` bucket, so neither account trusts the
      other for state.
  - **Wrong-account guard.** Each root's provider sets
    `allowed_account_ids = [var.account_id]`, supplied at run time (locally from
    `aws configure get sso_account_id --profile twr-dev`, in CI from a masked variable), never
    a literal. An `AWS_PROFILE` left on the management account then fails at `plan`.
  - **Phase 1 leftovers stay in the management account** until slice 1 destroys them; they are
    not moved or imported.
  - Multi-account Control Tower stays out of scope (D27).

**D13. Secrets: no credential is ever a Terraform value.** *Settled 2026-11-11.*
- **Where each kind lives.** Secrets Manager holds credentials that rotate or integrate with a
  service (RDS). SSM SecureString (free) holds static configuration secrets. Nothing goes in a
  task definition's `environment`; only `secrets` with `valueFrom` (retro §3).
- **RDS master.** The RDS-managed secret is created and deleted with the instance and encrypted
  with the CMK (`master_user_secret_kms_key_id`). RDS rotates it by itself (7 days by default).
  It is used only by the migration task (D9, D10).
- **Per-service database passwords** (`checkout_app`, `ticketing_app`):
  - generated as an **`ephemeral "random_password"`** and written with **write-only arguments**
    (`secret_string_wo` plus `secret_string_wo_version`, Terraform ≥ 1.11). The value never
    reaches state or plan;
  - *review:* write-only values are sent only when `secret_string_wo_version` changes, so
    **bumping the version is how a password rotates**. Since the secrets live in `session`, each
    new session gets fresh credentials; rotation by construction;
  - the migration task reads the secret and runs `CREATE ROLE … / ALTER ROLE … PASSWORD`
    idempotently on every run, so the role always matches the secret, even after a rotation;
  - services receive it through `valueFrom` with the JSON key
    (`<secret-arn>:password::`). A rotation reaches a service at its next deployment.
  - *Review:* the migration task changes the role's password **immediately**, while running
    tasks keep the old one until redeployed. New connections from those tasks would then fail.
    So a rotation is always **bump version → migrate → redeploy** within one `session.sh` run
    (D23), never a lone `apply`.
- **Cognito M2M secret.** It flows from `aws_cognito_user_pool_client` into the EventBridge
  connection and is never seen by a human. It is the **one secret that lands in state**,
  because the provider returns it as an attribute; that is accepted and is why the state bucket
  is locked down (D11). The connection stores it in its own `events!connection/…` secret.
- *Review: third-party keys are set out of band.* When a real payment gateway replaces
  `MockPaymentGateway`, Terraform creates the secret **container only**, with no version. The
  owner sets the value once with `aws secretsmanager put-secret-value`. A provider API key then
  never exists in git, in a `.tfvars`, in state or in a CI log. This is the standard pattern for
  any secret Terraform did not mint, and it is why CLAUDE.md lists Secrets Manager for payment
  keys.
- **Edge.** There is no edge secret: D5 removed `X-Edge-Secret`.
- *Cost:* 4 secrets (RDS master, two service passwords, the EventBridge connection's) ×
  $0.40/month prorated hourly, all session-only. About a cent per session.

### Delivery

**D14. GitHub Actions + OIDC: four roles, a protected `main`, and nothing private in public logs.** *Settled 2026-11-11.*
*Context found in review: the repository is **public**, and `main` has **no branch protection
or ruleset**.* Both facts change this entry.
- **No static AWS keys anywhere**, which matches the SSO-only account. The OIDC provider and the
  roles are created in `bootstrap/`, because CI needs them before it can apply anything.
- *Review: the trust chain starts at `main`, not at IAM.* "Only `main` may apply" means nothing
  while `main` can be force-pushed or committed to directly. Before the first OIDC role exists,
  a **ruleset on `main`** requires:
  - a pull request;
  - passing status checks (`ci`, `terraform` gates);
  - linear history;
  - no force-push and no deletion.

  The owner keeps an explicit bypass for emergencies, which is visible in the ruleset rather
  than implied.
  - *Changed 2026-11-13: the owner keeps direct pushes to `main` while slice 0 is scaffolding.*
    Until `bootstrap/` is applied, `main` carries no AWS trust, so nothing is lost. The ruleset
    is created **before `bootstrap/` is applied**, not later: from then on a push to `main` can
    reach AWS. The owner's bypass then keeps direct pushes possible for the owner alone,
    recorded in the ruleset's bypass log, while CI tokens and anyone else need a PR.
- *Review: four roles, split by what each can damage.* (The first draft had two. A review pass
  added `ci-images`: nothing else could push an image or record its digest.)

  | Role | Assumable from (`sub`) | Can | Cannot |
  |---|---|---|---|
  | `ci-images` | `images.yml@refs/heads/main` (via `job_workflow_ref`) | push to the project's ECR repositories; `ssm:PutParameter` on `/twr/dev/images/*` only | anything else |
  | `tf-plan` | `repo:<owner>/<repo>:pull_request`, and `drift.yml@refs/heads/main` | read AWS config, read state, write **only** the `.tflock` objects (`use_lockfile` takes a lock even for `plan`, the classic first-CI failure) | read data planes: denied `s3:GetObject` outside the state bucket, `secretsmanager:GetSecretValue`, `ssm:GetParameter*` on SecureStrings, `dynamodb:GetItem/Query/Scan` |
  | `tf-apply-foundation` | `repo:<owner>/<repo>:environment:foundation` (required reviewer) | everything in foundation, **IAM included, only under the permissions boundary** | create a role without the boundary, edit the boundary, touch `bootstrap` |
  | `tf-apply-session` | `repo:<owner>/<repo>:environment:session` (no reviewer) | create and destroy session resources; `iam:PassRole` on the project's roles; `iam:CreateServiceLinkedRole` for ECS, ELB, RDS, ElastiCache and Application Auto Scaling | **any IAM write** |

  - The split works because **every IAM role lives in foundation** (D12). The workflows that run
    often and unattended (session, reaper) therefore hold a role that cannot escalate
    privileges, and the only role that can is behind a human approval.
  - *The permissions boundary* (on `tf-apply-foundation`): it may call `iam:CreateRole` /
    `iam:PutRolePolicy` only with `iam:PermissionsBoundary` equal to the project boundary
    policy. Every role `modules/iam` creates carries it, and the boundary denies IAM
    administration and anything outside the project's services.
- *Review: `plan` is not read-only from a security standpoint.* `terraform plan` runs provider
  code and data sources; an `external` or `http` data source in a PR executes arbitrary code with
  the plan role's credentials. That is the reason `tf-plan` is denied every data plane above.
  - GitHub issues no OIDC token to workflows from **forks**, and no secrets either, so a
    stranger's PR gets no plan.
  - Plans run only for branches in this repository.
- *Review: pin the trust to the workflow file, not just the repository.* GitHub lets a repository
  customise its OIDC `sub` claim to include `job_workflow_ref`. With that, the foundation role
  trusts only `.github/workflows/terraform.yml@refs/heads/main`, not any workflow someone adds
  in a PR that later merges. This is set once in bootstrap
  (`PUT /repos/{owner}/{repo}/actions/oidc/customization/sub`), and the trust policies match the
  full string. Never a wildcard on the repository.
  - *Found 2026-11-13:* GitHub issues the **immutable** subject for this repository:
    `repo:<owner>@<owner-id>/<repo>@<repo-id>:…`. The numeric ids make a deleted-and-recreated
    repository with the same name unable to assume the roles. The trust policies use that
    prefix, and the first OIDC run (slice 4) prints its claims before any apply relies on it.
- *Review: Actions supply-chain baseline.*
  - **Third-party actions are pinned to a full commit SHA**, with the version as a comment. A
    moved tag on a popular action leaking secrets from every workflow that used it (March 2025)
    is why this is standard now. Dependabot (D2) updates the SHAs.
  - `permissions:` is set per workflow: `contents: read`, `id-token: write` only on jobs that
    assume a role, `pull-requests: write` only on the plan-comment job, and `issues: write` only
    on `drift.yml`. The default
    `GITHUB_TOKEN` is read-only in the repository settings.
- *Review: public logs and the identifier rule (CLAUDE.md *Secrets and Identifiers*).*
  - On a public repository, **Actions logs are public**, and Terraform output is full of ARNs
    carrying the account id, ALB DNS names and API ids. The rule that keeps these out of git
    applies to a public log just as much.
  - Every job that assumes a role first runs `aws sts get-caller-identity` and registers the
    account id with `::add-mask::`, so every later occurrence prints as `***`. API and ALB
    hostnames are masked the same way as soon as a step learns them.
  - The **PR comment** carries only the change summary: resource addresses and actions, taken
    from `terraform show -json`. Addresses never contain an identifier; a full plan does.
  - Full plans stay on the runner. They are **not** uploaded as artifacts, which anyone can
    download on a public repository.
  - **`workflow_dispatch` inputs print in the public log.** So nothing identifying is ever an
    input. In particular, the load-test allow-list address (D6) is applied only from the owner's
    machine, never through `session.yml`.
- **Concurrency.** `session.yml` and `session-reaper.yml` share one `concurrency` group, so the
  reaper can never destroy a session while it is being applied. `use_lockfile` would refuse the
  second run anyway, but failing fast at the queue is clearer than a lock error.

**D15. Workflows.** *Settled 2026-11-11.*

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | PR, push | `./gradlew build` with `gradle/actions/setup-gradle` caching (tests including Testcontainers) |
| `images.yml` | push to `main` | build each **affected** image on a native arm64 runner, push to ECR, record its digest (below) |
| `terraform.yml` | PR / `main` | gates (D2) + `terraform test`, plan summary on the PR (D14), `apply foundation` on `main` behind the `foundation` environment |
| `session.yml` | manual, toggles as inputs | runs `infra/scripts/session.sh up` (D23) → `smoke.sh` → invariants (D16) → optional `session.sh down` |
| `session-reaper.yml` | **hourly** cron | destroy the session if it is older than its allowed age (below) |
| `drift.yml` | nightly cron | `plan -detailed-exitcode` on foundation; exit 2 opens an issue |

- The ephemeral `apply → smoke → destroy` run *is* Phase 2's version of slice 5's cold rebuild
  (retro §9): "re-creatable, not standing" becomes a test.
- *Review: "touching a module" is the wrong filter.* A change to `common`, either starter,
  `gradle/` or the root build changes every service image. The filter for each image is its
  module **plus every module it depends on plus the build files**, written once as a matrix map.
  Missing one is how a stale image runs green, which the retro already met once (`6a55391`).
- *Review: where the digests live, and how a rollback works.* "Output digests" was not a design.
  - `images.yml` (as `ci-images`, D14) writes each digest to SSM `/<prefix>/<env>/images/<service>` (digest only;
    the registry host is built at run time from the caller's account, so no identifier is
    stored).
  - `session.yml` resolves those parameters **at dispatch** into explicit `-var` digests, and
    prints them in the job summary. Terraform takes digests only as variables and never reads
    SSM for them, so the plan shows exactly what will run.
  - Every service also has an optional digest **input**: a rollback is re-running `session.yml`
    with the previous digest, which the earlier run's summary recorded.
  - Phase 3 moves this to true GitOps, with digests committed and Argo CD reconciling. For now
    SSM is the pointer, and git is not, because a bot commit to a protected `main` needs a
    GitHub App with bypass rights for no gain this phase.
- *Review: the reaper must not kill a working session.* A blind nightly destroy also destroys the
  session you are debugging at 23:00.
  - `session.sh up` writes `/<prefix>/<env>/session/expires_at` (default: start + 4 h,
    overridable by an input).
  - The hourly reaper destroys only after that time.
  - A session that should live longer is extended by re-dispatching with a new expiry, never by
    disabling the reaper.
- *Review: drift detection.* The nightly plan on foundation catches a console edit, or an AWS-side
  default change, the day it happens rather than at the next session. Drift is how the retro's
  §4 hand-made leftovers accumulated.
- **Builds and scans.**
  - Native arm64 runners (`ubuntu-24.04-arm`) instead of QEMU, several times faster for a JVM
    build.
  - ECR **basic scan on push** (free), and a `buildx` SBOM attached to each image.
    *Found 2026-11-15:* **not for the Lambda images.** An attestation (SBOM or provenance) turns
    the push into an OCI image index, and Lambda accepts only a single image manifest. Lambda
    images are built with `--provenance=false --sbom=false`; their SBOM, if wanted, is generated
    separately (`docker buildx imagetools` / syft) and stored as a build artifact.
  - Lambda images go through the same `images.yml`. Because the Lambdas are in foundation, a new
    Lambda digest is deployed by the approved foundation apply (D23).
- *Cost.* Public repositories get standard GitHub-hosted runners, arm64 included, at no charge.
  If the repository goes private, the 2,000 free minutes a month cover this, and the arm64
  runner pricing should be re-checked then.

**D23. Terraform deploys the services, in stages.** *(new)* *Settled 2026-11-11.* A deploy is `terraform apply` of
`session` with digest variables, so Terraform owns the task definitions.
- *Why here, honestly.*
  - The more common pattern in large long-lived fleets is CI registering a task definition and
    calling `ecs update-service` (or ecspresso, or CodeDeploy), with Terraform ignoring
    `task_definition`. It keeps a deploy small and fast.
  - This project is different on both counts: the session stack is **created from nothing** at
    almost every deploy, and the retro's main lesson is values drifting between two places. One
    tool owning the task definition wins here.
  - In Phase 3, Argo CD takes over deploys, which is where the split becomes the right answer.
- *Review: ordering cannot live inside one apply.* The refund path needs seat-inventory's
  idempotent confirm deployed first (`runbook-checkout-saga.md`), and migrations must run after
  the database exists but before checkout and issuance start. Terraform's graph cannot run a
  migration task without `local-exec`, which is an anti-pattern: invisible in the plan,
  unretried, and running on whichever machine applies. So **`infra/scripts/session.sh up`**
  applies in stages, driven by the existing `services` toggle. It is one script, run by the
  owner in slices 2–3 and by `session.yml` from slice 4, so the order lives in exactly one place
  and the workflow stays a thin wrapper:
  1. `apply` with data, edge and `services = ["seat-inventory"]` (waits for steady state);
  2. `infra/scripts/migrate.sh`: `aws ecs run-task --wait` for each database, failing the
     workflow on a non-zero exit;
  3. `apply` with every service.

  The order is code, not memory, and each stage is visible in the plan.
- *Review: what a circuit-breaker rollback leaves behind.* When ECS rolls a failed deployment
  back, the service points at the previous task-definition revision while Terraform state still
  holds the new one. The apply fails (`wait_for_steady_state`), and the next plan shows the
  difference. The documented response is to re-run with the previous digest (D15), not to
  `refresh` the drift away.
- **Lambdas.** They are foundation resources, so their new digests ship through the approved
  foundation apply. Lambda code changes rarely, and the approval is acceptable.

### Operations

**D16. Alarms as code: symptoms that need a human, each linked to its runbook.** *Settled 2026-11-11.*
- *Review: the first draft was a list of metrics. An alarm design needs a rule.* An alarm exists
  only if a human has to **do** something when it fires. Every alarm's `alarm_description` names
  the runbook section that says what. An alarm with no action is a dashboard widget, and the
  dashboard already exists (`docs/dashboard-ticket-waiting-room.json`).
- **The alarms, by what they mean.**

  | Alarm | Fires when | Means | Stack |
  |---|---|---|---|
  | Every **DLQ** (5, incl. the SNS subscription's) | `ApproximateNumberOfMessagesVisible > 0` | a message gave up; a human must replay it or explain it | foundation |
  | Oldest message on the main queues (4) | `ApproximateAgeOfOldestMessage` > 3× the visibility timeout | a stuck consumer, long before anything reaches a DLQ | foundation |
  | Lambda errors (3 functions, one metric-math alarm) | `Errors + Throttles > 0` over 5 min | the notifier dropped a burst, or QR / cleanup failing | foundation |
  | **Saga needs a human** | `ExecutionsFailed > 0` | one of the four Fail states (`RefundFailed`, `PaidButNotConfirmed`, `PaidButNotIssued`, `CompensationFailed`) | session |
  | ALB target 5xx | `HTTPCode_Target_5XX_Count` > 1% of requests | a service failing | session |
  | Unhealthy / missing tasks | `UnHealthyHostCount > 0` or `RunningTaskCount < desired` for 5 min | a crash loop, or a Spot interruption that never came back | session |
  | Admission publish failure | metric filter on the swallowed error | a buyer was admitted but never told, so the queue position is lost | foundation |

  - The alarms on session resources live in **session**. They reference the ALB, the services
    and the state machine, so the retro's rule puts them there, and they cost nothing when no
    session is up.
- *Review: pin the settings that make alarms silently useless* (the retro §3 treatment):
  - **`treat_missing_data`** is explicit on every alarm. SQS stops publishing metrics for an
    idle queue, so with the default (`missing`) a DLQ alarm sits in `INSUFFICIENT_DATA`, which
    is neither green nor red. DLQ, age and error alarms use `notBreaching`; the health and
    task-count alarms use `breaching`, because a service that publishes nothing is not healthy.
  - `ok_actions` as well as `alarm_actions`, so the inbox shows recoveries too.
  - Thresholds as variables, and every evaluation period written out.
- *Review: the alarm topic and the CMK (D11).* `ops-alarms` is an SNS topic, so the D11 rule
  encrypts it. **CloudWatch, Budgets and Cost Anomaly Detection can only publish to an encrypted
  topic if the key policy names them** (`cloudwatch.amazonaws.com`, `budgets.amazonaws.com`,
  `costalerts.amazonaws.com`). With the AWS-managed `aws/sns` key, they *cannot* be granted, and
  the alarm "fires" with nothing delivered. This is the most common way an alarm setup is
  silently dead. One `terraform test` asserts the three statements, and slice 5 proves delivery
  by forcing an alarm.
- **The email subscription** needs a one-time click to confirm; Terraform cannot do it. It lives
  in foundation, so it is confirmed once, and slice 1's checklist includes the click.
- *Review: the project's own invariant becomes a check, not a script someone remembers.*
  The reconciliation in `load-test/scripts/invariants.sh` (SOLD = PAID = CAPTURED = tickets,
  0 double-sold) runs as the **last step of every session, before the destroy**, and fails the
  run on a mismatch.
  - *Review: it cannot run where it runs today.* The script calls `psql` against the RDS host
    from the operator's machine, as the master user, against the hard-coded table
    `seat-inventory`. In Phase 2 the database is private (D9), the table is `twr-dev-…` (D25),
    and the master user is migration-only.
  - So it becomes a **one-off ECS task**, the same pattern as the migration (D10): it runs inside
    the VPC, reads the table name from the environment, connects with a read-only role, and
    returns its result as an exit code.
  - `load-test/README.md` and the runbook are updated in the same change. In production this is a scheduled reconciliation job: the standard way payment
  systems catch money taken without a ticket. Here every session ends with the proof the whole
  project exists to give.
- **Also as code.**
  - The existing dashboard JSON becomes `aws_cloudwatch_dashboard` via `templatefile()`. With
    deterministic names (D25), it lives in foundation and fills whenever a session runs.
  - The Logs Insights queries the runbooks use become `aws_cloudwatch_query_definition`s (free).
  - The collector gets `detailed_metrics: true` for server-side p99 (retro §7).
- *Built 2026-11-16, two differences from the text above.* (1) The fifth DLQ is the SNS
  subscription's own (`<topic>-to-<queue>-dlq`), which did not exist yet: without it SNS
  discards what it cannot deliver. It is in `modules/messaging`. (2) The admission-publish alarm
  reads the counter `waiting_room.admission.publish.failures`, which the service already exports
  through the collector, instead of a metric filter on the log line: same signal, one fewer
  metric, and no regex to keep in step with a log message.
- *Cost:* foundation alarms are 16 metrics (5 DLQ + 4 age + 6 Lambda + 1 filter): 6 beyond the
  free 10, so about **$0.60/month standing**. The session alarms (about 14 metrics) are prorated
  hourly, so they cost cents.
- *Not chosen:*
  - **CloudWatch Synthetics canaries.** Billed per run; `smoke.sh` in CI is the synthetic check
    for a stack that exists only during sessions.
  - **Composite alarms.** Worth it once alarms cascade into each other; eight alarms do not.

**D17. Cost guardrails as code, with honest expectations of each.** *Settled 2026-11-11.*
- **The layers, fastest first.** A *budget* is the slowest guard, not the first one.

  | Guard | Reacts in | Catches |
  |---|---|---|
  | `session-reaper` (D15) | ≤ 1 h after `expires_at` | a forgotten session, which is the realistic way to overspend |
  | **Infracost** on PRs | before merge | a change that makes a stack more expensive (below) |
  | Cost Anomaly Detection | about a day | spend in a shape it has not seen, e.g. a NAT Gateway appearing at 3 am |
  | Budgets | 8–24 h (billing data lags) | totals: $10 monthly (actual 80% / 100%, and **forecasted** 100%), $20 Phase 2 cumulative (a custom period from Phase 2's start) |
  | `teardown-sweep.sh` after every destroy | immediately, by hand | anything Terraform did not create; its TAGS tier must print nothing |

  - The honest point: budgets fire after the money is spent. They are the record, not the
    brake.
  - *Review: the $1 zero-spend budget is retired.* With about $2.5–3/month standing, it would
    fire every month. That makes it an alarm with no action, which D16's rule forbids. Two
    budgets remain, which also keeps Budgets free. (A $1 budget **does** fit the management
    account, where nothing should bill; it lives in `infra/org/`, D12.)
  - *Changed 2026-11-13: budgets email the owner directly* as an email subscriber, and also
    notify `ops-alarms`. The slowest guard must not depend on a topic, its CMK grant and a
    confirmed subscription all working at once.
- *Review: Infracost on infrastructure PRs.* The free CLI prices the plan and posts the
  difference as a PR comment.
  - Infracost assumes resources run all month, which for `session` is exactly **the price of
    forgetting it**. That is the number to see before merging, and it keeps CLAUDE.md's "every
    service must earn its place" honest.
  - It needs a free API key, stored as a GitHub Actions secret, never in a file.
  - The comment shows prices and resource addresses only, so no identifiers (D14).
- **Cost allocation.** `project`, `stack` and `env` are activated as cost-allocation tags
  (`aws_ce_cost_allocation_tag`) in slice 0. Activation applies only **forward** and takes up
  to 24 h, which is why it is done before the first session, not after it. Cost Explorer can
  then answer "what did sessions cost versus standing" by `stack`.
- *Cost:* $0. Two budgets, anomaly detection, cost-allocation tags and Infracost's
  CLI are all free. Cost Explorer API calls are $0.01 each (retro §8), so workflows never call it.

**D24. Data protection and the audit trail.** *(new)* *Settled 2026-11-11.*
- **Audit trail first: CloudTrail.** *Review: the first draft had no audit log at all,* and an
  account without one is the first finding of any review. Phase 1 already needed it, to find
  which `RunTask` started a task (`runbook-checkout-rds.md`).
  - One multi-region trail, management events only, delivered to an S3 bucket.
  - Log-file validation on.
  - A 90-day lifecycle, and the bucket in foundation.
  - The first copy of management events is free, so the cost is S3 storage, pennies.
- **Three account-level alerts** go to `ops-alarms` through EventBridge rules on CloudTrail
  events. They are free, and they are the CIS benchmark classics, for the events that mean
  something is badly wrong:
  - **root user sign-in**;
  - **KMS key disabled or scheduled for deletion**: the CMK going away makes RDS, S3, SQS and
    SNS unreadable (D11);
  - **an IAM change made outside Terraform**: any caller other than `tf-apply-foundation`
    creating or changing a role or policy, excluding service-linked roles that AWS creates. It
    is drift detection (D15) for the one resource type where drift is a security event.
  - *Review: the region trap.* IAM, root sign-in and other global-service events are delivered
    in **us-east-1**, not eu-central-1. An EventBridge rule in eu-central-1 matches nothing and
    never errors. So the IAM and root rules live in us-east-1, through a second, aliased
    provider in foundation. They forward to eu-central-1's default event bus, where one rule
    targets `ops-alarms`. The KMS rule stays regional.
- **DynamoDB, the source of truth for what is sold.**
  - Point-in-time recovery, with `recovery_period_in_days = 7`.
  - `deletion_protection_enabled = true` behind a variable.
  - Use AWS-side protection rather than Terraform's `prevent_destroy`. AWS-side protection also
    stops the console, and `lifecycle` cannot be driven by a variable. Ending the phase is
    documented as: flip the variable → apply → destroy.
- **Cognito user pool.** `deletion_protection = "ACTIVE"`. In production, users and their
  passwords are the one store that cannot be rebuilt from code or a seed. Here the test users
  come back from `create-test-users.sh`, but the switch is set the production way.
- **ECR.** `image_tag_mutability = "IMMUTABLE"`. Deploys go by digest anyway (D15), and
  immutability guarantees a tag can never be repointed under a running task. The lifecycle
  policy keeps the last 3 images per repository.
- **RDS: production values documented, dev values deliberate.** The instance is rebuilt each
  session from Flyway (settled in the retro), so dev sets `backup_retention_period = 0`, which
  is a faster create with no backup storage. That carries a trivy suppression, with the reason
  in a comment. The documented production values are 7-day backups with PITR,
  `deletion_protection = true` and a final snapshot. They are one variable each, so the
  difference is visible in code rather than in a sentence.
- **S3.**
  - The state bucket is versioned (D2).
  - The QR bucket is **not** versioned: an object is re-rendered byte-for-byte from the ticket
    id (CLAUDE.md, *QR generation is idempotent by construction*), so versions would store
    duplicates.
  - All buckets block public access and have `BucketOwnerEnforced` object ownership.
  - The WAF, flow-log and CloudTrail buckets have lifecycle expiry.
- *Not chosen:* **AWS Backup.** It is the right tool when backup plans must be centrally governed
  and audited across many stores and accounts. Here, PITR on the one standing table does the job
  at a fraction of the moving parts.

### Open functional work, seams and records (retro §7)

**D18. WebSocket: authenticate `$connect` with a single-use connect ticket, which also caps the
fan-out.** *Settled 2026-11-11.*
- *Review: the cap was the smaller half of the problem.* Today `$connect` is a mapping template
  that writes whatever `eventId` and `token` the query string carries (`runbook-websocket-queue.md`:
  "authenticating a token is an authorizer's job"). So it is **unauthenticated**: anyone can
  open sockets, under anyone's queue token, in a loop. WebSocket APIs accept neither a Cognito
  nor a JWT authorizer (only IAM or a Lambda REQUEST authorizer), and WAF cannot attach to them.
  Whatever closes this gap has to be designed, not configured.
- **Design: the connect-ticket pattern**, the standard answer for WebSocket auth in browsers,
  which cannot set an `Authorization` header on a socket.
  1. **Issue.** The buyer, already authenticated by JWT, asks `waiting-room-service` for a
     connect ticket for their queue token. The service first checks the caller's `sub` owns
     that token. It then writes one DynamoDB **`TransactWriteItems`** into the connections
     table (single-table, prefixed values of its partition key `connectionId`):
     - an update to `cap#<token>`, `ADD issued 1` with the condition `issued < :k`, and a
       10-minute TTL;
     - a put of `ticket#<random>`, holding `{ticketEventId, ticketToken, expiresAt = now + 60 s}`.
     - *Review: the attribute names are deliberate.* The notifier's GSI is keyed on `eventId`
       and `token`. A ticket item carrying those two attributes would be **indexed as a
       connection**, and the notifier would try to push to `ticket#…`. Using different names
       keeps ticket and cap items out of the sparse index. A test asserts the GSI query returns
       only real connections.
     - `expiresAt` is epoch **seconds**: the retro's milliseconds trap (`runbook-websocket-queue.md`)
       applies here too.

     Both happen or neither does, and the cap is a **conditional write**.
  2. **Consume.** The browser opens `wss://…?ticket=<id>&eventId=…&token=…`. The `$connect`
     integration becomes one `TransactWriteItems`, still **no Lambda**:
     - delete `ticket#<id>` with the condition `attribute_exists(connectionId) AND
       ticketToken = :token AND ticketEventId = :eventId AND expiresAt > :now`, where `:now` is
       `$context.requestTimeEpoch / 1000`;
     - put the connection row exactly as today.

     A failed condition cancels the transaction, which fails the handshake.
- **Why this one.**
  - It is the project's thesis applied again. A ticket is single-use **by conditional delete**,
    so two sockets racing on one ticket produce exactly one connection. The same race-test style
    as the seat hold proves it.
  - `$connect` stays a service integration. CLAUDE.md's rule ("a Lambda only when something has
    to *decide*") holds: the decision moved to the issuance side, which is Java, testable and
    authenticated already.
  - A Java Lambda authorizer would put a 2.8 s cold start (retro §9) on every handshake at
    door-open, which is the one moment that cannot afford it.
  - A JWT in a query string ends up in access logs. A 60-second single-use ticket in a log is
    worthless by the time anyone reads it.
- **What the cap honestly bounds.** It limits *new sockets per token per 10 minutes* to `k`
  (default 5: tabs plus reconnects), so live sockets per token are bounded by `k` per window.
  An exact live-socket count would need `$disconnect` to decrement a counter it cannot find by
  key, which takes a Lambda again. That precision is not worth the Lambda, and the reason is
  written down.
- **Route throttling** on `$connect` (`route_settings` rate and burst) bounds how fast *anyone*
  can open sockets: free, and the WebSocket stand-in for a WAF rate rule.
- **Spike first in slice 6.** `TransactWriteItems` through an API Gateway service integration,
  and the exact error mapping so a cancelled transaction surfaces as a refused handshake, not
  an open socket.
- *Fallback:* a Lambda REQUEST authorizer on `$connect` that validates the same ticket. Its
  result is cached by the ticket, so a retried handshake does not pay twice.
- **Code work.**
  - A `POST /events/{eventId}/queue/socket-ticket` endpoint in `waiting-room-service`. Its
    first DynamoDB use, so it needs a table policy scoped to the `cap#*` / `ticket#*` keys
    (`dynamodb:LeadingKeys`).
  - The `$connect` template change.
  - A race test: N threads presenting one ticket → exactly one connection.
  - Update `QueueSocketIT` and the module's `ARCHITECTURE.md` in the same change.

**D19. The QR's target: ticket verification and an atomic check-in at the gate.** *Settled 2026-11-11.*
- *Review: the question was framed too narrowly.* "Who may `GET /tickets/{id}`" has a real-world
  answer: the people who scan tickets are **gate staff**, and the operation that matters is
  **admitting someone through the gate exactly once**. A screenshot of a QR shared with a friend
  is the double-sold seat's physical twin: two people, one seat, two gates scanning at the same
  second.
- **Design.**
  - **`GET /tickets/{ticketId}`**, the exact URL every QR already encodes
    (`TicketQrObjects.verificationUrl` → `<base>/tickets/<id>`), returns validity, event, seat
    and check-in state. It needs a new scope **`ticketing/gate`**, held by a new Cognito M2M
    client for scanners. The owner's own view stays `GET /tickets/{id}/qr` (`sub` ownership,
    unchanged).
  - **`POST /tickets/{ticketId}/check-in`** is
    `UPDATE tickets SET checked_in_at = now(), checked_in_by = :gate WHERE id = :id AND
    checked_in_at IS NULL`. One row updated means admitted; zero means "already used at
    <time>, <gate>". The decision is the database's, not a read-then-write in Java, which is the
    same rule CLAUDE.md applies to issuance.
  - A Flyway migration adds the two columns.
  - **Concurrency proof:** N gate threads check in one ticket → exactly one admitted, N−1 told
    when and where. A test that races real threads, per CLAUDE.md.
- **The QR URL needs a stable host.** Today the QR encodes `TICKET_VERIFY_BASE_URL`, which is
  the `{api-id}.execute-api…` URL. That is stable for as long as foundation exists, because the
  REST API lives there (D5). It breaks only if foundation is rebuilt. A custom domain would make
  it permanent; it is deferred (D20), with this gap accepted.
- *Future, documented: offline and anti-screenshot QR.* Production ticketing signs the QR
  (verifiable at a gate with no network) and rotates it every few seconds from a per-ticket
  secret, so a screenshot expires. That needs an asymmetric KMS key and a client app.
  Online check-in plus the atomic `UPDATE` already defeats the screenshot *at the gate*, which
  is the part this project exists to show.

**D20. Custom domain (Route 53 + ACM + SES DKIM).** *Deferred 2026-11-11 by the owner, to keep
budget margin under $20. Earlier the same day it had been settled as opted in.*
- **What it would solve, and what happens instead.** These are accepted gaps, written down so
  they read as decisions, not oversights:

  | Problem | With a domain | Phase 2 without one (accepted) |
  |---|---|---|
  | Email deliverability | SES domain identity with DKIM/SPF/DMARC, so mail reaches the inbox | SES address identities and the sandbox. Mail reaches only verified addresses and may land in spam. The proofs only need verified addresses |
  | TLS from the VPC link to the ALB | free public ACM certificate on the internal ALB | **plain HTTP inside the private VPC.** The ALB admits only the VPC link's SG (D5). Common outside regulated workloads; a private CA (~$400/month) is not worth it |
  | QR URL | `https://api.<domain>/tickets/<id>`, stable forever, no API id visible | `{api-id}.execute-api…/dev/tickets/<id>`. It is **stable for the life of foundation**, because the REST API lives there (D5) and survives every session; it changes only if foundation itself is rebuilt |

- **Cost avoided:** about $3–5 for registration plus $0.50/month for the hosted zone. That takes
  the phase estimate from ≈ $18–20 down to ≈ **$15–17** (§6).
- **What would bring it back:** a frontend (CloudFront needs a certificate and a name), real
  users receiving email, or Phase 3. The design notes stay valid:
  - register by hand, then let Terraform own the hosted zone via
    `aws_route53domains_registered_domain`, with no import;
  - domain first, before any ticket is issued, so no QR carries the old URL;
  - SES production access is a one-time request.
- *Consequence for the identifier rule (CLAUDE.md):* QR codes generated in sessions embed the API
  id. They are runtime artefacts in S3, never committed, so the rule is not affected. Screenshots
  of QR codes in docs must blur or replace the host with `<rest-api-id>`.

**D21. Phase 3 seam: make the modules compute-agnostic now.** *Settled 2026-11-11.*
- **Carried.** ECS services and task definitions live in `modules/ecs-service` and own nothing
  else. EKS gets its **own root stack** (`live/eks`), applied only for EKS sessions. A 10–15
  minute control plane at $0.10/h does not belong in every session.
- *Review: the seams that make "replace one module" actually true.* Phase 3 should swap
  `ecs-service` for a Kubernetes deployment and keep everything else. Four choices now make that
  real:
  - **IAM: permissions separate from trust.** `modules/iam` builds each service's permission
    policy once and takes the **trust policy as an input**: `ecs-tasks.amazonaws.com` now, EKS
    Pod Identity (`pods.eks.amazonaws.com`) later. The same least-privilege policy serves both
    platforms; only the trust changes.
  - **ALB: Terraform keeps it.** The AWS Load Balancer Controller's **`TargetGroupBinding`**
    registers pods into an *existing* target group. The ALB, listeners, rules and target groups
    stay in this module unchanged. Only what registers targets changes, from ECS to the
    controller.
  - **Subnet tags now.** The network module tags app subnets
    `kubernetes.io/role/internal-elb = 1` (free) and they are already `/20` (D3). EKS then finds
    them without a network change.
  - **Configuration through environment variables only**, with secrets via `valueFrom` (D13).
    In Phase 3 the External Secrets Operator writes the same variable names, so the services do
    not change. The collector sidecar becomes a DaemonSet (retro).
- *Review: per-service security groups have a Kubernetes twin.* Security Groups for Pods keep
  D3's SG-to-SG rules meaningful on EKS. The alternative is network policies, which would
  rewrite them. Decide in Phase 3, but D3's per-service SG design is what makes either one
  possible.

**D25. Naming: short, deterministic, and never hand-copied.** *(new)* *Settled 2026-11-11.*
- **The convention.** Every name is built in one `locals` per root:
  `${var.prefix}-${var.env}-<name>`, with the region as a variable. Only `dev` exists, but a
  second environment becomes a new `live/<env>/` directory, not a rename across forty resources.
- *Review: use a short prefix.* AWS name limits bite here. ALB and target-group names are
  **≤ 32 characters**, and `ticket-waiting-room-dev-seat-inventory` is already 38. So
  `prefix = "twr"` (`twr-dev-seat-inventory-tg`), while the `project` *tag* keeps the full
  `ticket-waiting-room` for cost allocation and the sweep. A `validation` block on every
  name-bearing variable rejects anything over the limit at plan time, not at apply.
- *Review: globally unique names, computed, never committed.* S3 bucket names are global, so they
  carry the account id. It is taken from `data.aws_caller_identity` at plan time, never
  written in a file (CLAUDE.md *Secrets and Identifiers*).
- *Review: services read names, never hard-code them.* Table, queue, topic and bucket names
  reach the services only through task-definition environment variables set from module
  outputs. `seat-inventory-service`'s `application.yml` keeps `seat-inventory` only as the local
  default.
- *Review: the new names avoid the Phase 1 collision.* With the prefix, nothing collides with
  the hand-built `seat-inventory` table or queues, so foundation can be applied **before** the
  Phase 1 resources are destroyed. The retro's destroy-don't-import (§4 *Carried Over*) still
  holds; it just stops being a blocking first step.
- **Deterministic names** are also what let the dashboard and the session alarms (D16) be
  written without looking anything up.

**D22. Lambdas stay outside the VPC.** *(review: no change).* *Settled 2026-11-11.* The reasoning holds:
- none of the three needs a private resource;
- in the VPC they would add the ENI attach to an already-measured 2.8 s INIT;
- they would also break whenever the session-only NAT is down.

The rule in `modules/lambda-function`: a Lambda joins the VPC only to reach RDS or Valkey.

**D26. Architecture decision records, for the significant decisions only.** *(new)* *Settled 2026-11-11.*
- *Review: one ADR per D-entry would be ceremony.* Twenty-seven ADRs written in one week is a
  log nobody reads. Industry practice is an ADR for a decision that is **expensive to reverse
  or likely to be questioned later**. That gives eight here:

  | ADR | Decision |
  |---|---|
  | 0001 | Hybrid modules (D1) |
  | 0002 | Two stacks, foundation and session (retro §1, D2) |
  | 0003 | Private ALB behind REST + VPC Link v2 (D5) |
  | 0004 | One database role per service, migrations as a deploy step (D9, D10) |
  | 0005 | Four CI roles, with IAM writes only behind approval (D14) |
  | 0006 | Terraform deploys the services, in stages (D23) |
  | 0007 | WebSocket connect tickets (D18) |
  | 0008 | Phase 3 seams (D21) |

- **Format:** MADR (context, decision, consequences, alternatives considered) in `docs/adr/`.
- The other decisions stay in this plan, which is itself the decision log for the phase.
- The ADRs are written as each slice **builds** the decision, not now, so each one records what
  was actually built.

**D27. Explicitly not added, with the reason, plus one correction.** *(new)* *Settled 2026-11-11.*
- *Review: GuardDuty was excluded for a weak reason, and that is reversed.*
  - "Paid after its trial" is true, but GuardDuty bills by volume: CloudTrail events, flow logs
    and DNS queries. An account this size typically costs well under $1/month.
  - This account now has a **public repository with OIDC roles**: a visible target with a
    credential path. Threat detection is the baseline for exactly that.
  - **Enable GuardDuty in slice 0** (foundational detection only, no paid protection plans),
    read its own usage-cost estimate at the end of the 30-day trial, and keep it if the
    estimate is under $1/month.
  - Findings of severity Medium and above go to `ops-alarms` (EventBridge, free).
- **Still not added:**
  - **AWS Config.** It bills per configuration item recorded, and this project creates and
    destroys an entire stack per session. Config's cost scales with exactly what this project
    does most. trivy (before apply), drift detection (D15) and the IAM-change alert (D24) cover
    its job here.
  - **Security Hub CSPM.** It bills per check and aggregates findings from services this account
    mostly does not run. It is worth it with Config and several accounts.
  - **Control Tower / multi-account landing zone.** It is the production shape; this is one
    workload in one member account. The part that matters is already in place (D12): no
    workloads in the management account, root credentials removed, guardrail SCPs from
    `infra/org/`. Control Tower would add a log-archive and an audit account, and AWS Config
    in each, which is exactly the Config cost ruled out below.
  - **A service mesh / Service Connect.** There are no east-west calls (D7).
  - **CodeDeploy blue/green.** Rolling deploys with a circuit breaker are the ECS default.
    Progressive delivery arrives with Argo Rollouts in Phase 3.
  - **Multi-region.** The concurrency guarantees are single-region by design: one seat table,
    one queue. Global tables would turn the conditional write into last-writer-wins across
    regions, which is the opposite of the project's thesis.

---

## 4. Carried Over Unchanged (retro *What Phase 1 Settled*)

Concurrency mechanisms and their proofs; `$connect`/`$disconnect` as DynamoDB service
integrations; the saga's Choice-routed decline and refund-only compensation after capture;
Lambdas as `eclipse-temurin` + RIC images with the HTTP stacks stripped; OTLP to a collector
sidecar; RDS without final snapshot; **destroy the Phase 1 resources, do not import them**.

---

## 5. Slices

Working rhythm per slice: **(a) write the module + `plan` → (b) apply in a session →
(c) prove it → (d) second `plan` shows no changes → (e) destroy session, run
`teardown-sweep.sh` → (f) tick boxes, note findings here.**

### 0. Bootstrap and guardrails — $0
- [x] Re-price every session component from the Price List API; correct §6 — 2026-11-13: only RDS moved ($0.018 → $0.019/h)
- [x] Confirm provider support for regional NAT mode (D4) — AWS provider 6.73: `aws_nat_gateway.availability_mode`; also `aws_api_gateway_integration.integration_target` for REST → ALB over VPC Link v2 (D5)
- [x] Account check: it was the management account → member account `twr-dev` in OU `workloads`, root access management on, SSO assignment, CLI profile — by hand, 2026-11-13 (D12)
- [x] `infra/org/` (management account, own state bucket): SSO permission sets (day-to-day read + ECS Exec; admin for bootstrap and break-glass) with the console assignment imported; SCPs on `workloads`; management-account $1 budget; check a privileged root action still works with the root-deny SCP attached (D12) — applied 2026-11-13; the root check failed first and the SCP now exempts `aws:AssumedRoot`; CLI profile `twr-dev-readonly`
- [x] `infra/` layout and module conventions (D1), `.terraform-version`, multi-platform provider lock, `.tflint.hcl`, `allowed_account_ids` from a run-time variable in every root (D12) — Terraform 1.17.2, AWS provider `~> 6.73`, `infra/scripts/tf-env.sh` + `tf-init.sh`, `infra/README.md`
- [x] `.githooks/pre-commit` extended with `terraform fmt -check`; `.github/dependabot.yml` (D2) — weekly, grouped, 7-day cooldown
- [x] Minimal `ci.yml`: Gradle build + a `terraform` job (`fmt -check`, `validate`, `tflint`, no AWS credentials), so the ruleset has checks to require; slice 4 grows it (D14) — green on GitHub 2026-11-13 (`gradle`, `terraform`)
- [x] **Ruleset on `main`** (PR, required checks from the minimal `ci.yml`, linear history, no force-push, owner bypass) — **before `bootstrap/` is applied**; direct pushes until then (D14, changed 2026-11-13) — 2026-11-13 by `gh api`, with: default `GITHUB_TOKEN` read-only, OIDC `sub` customised, environments `foundation` (reviewer) and `session`
- [x] `bootstrap/`: state bucket (versioned, SSE, public access blocked, `use_lockfile`), OIDC provider with `job_workflow_ref` in `sub`, the four roles, the permissions boundary; repo default `GITHUB_TOKEN` read-only (D14) — applied 2026-11-14 (20 resources), state migrated to the bucket, second plan clean; the read-only permission set is refused the state (403)
- [x] *(applied 2026-11-14; email subscription confirmed and a test message published 2026-11-14)* Foundation skeleton, applied locally: `kms` (key policy with the CloudWatch, Budgets and anomaly grants, D11) and `ops-alarms` topic on the CMK, email subscription **confirmed by click** — slice 0's GuardDuty and CloudTrail alerts need a target (D16)
- [x] `modules/budgets` (actual + forecasted alerts, Phase 2 custom period, owner email as a **direct** subscriber as well as `ops-alarms`), Cost Anomaly Detection; `project`/`stack`/`env` cost-allocation tags activated **before the first session** (D17) — applied 2026-11-14 (`CUSTOM` time unit accepted for the phase budget); the tags moved to `infra/org/cost_tags.tf` because activation is a payer-account setting: add each key to `cost_allocation_tag_keys` once Billing lists it — 2026-11-15: `project` activated (second plan clean); `stack`, `env` still to appear
- [x] GuardDuty on (foundational only), Medium+ findings → `ops-alarms`; read the trial's cost estimate at day 30 (D27) — applied 2026-11-14; **read the usage estimate on 2026-12-14**
- [x] CloudTrail trail (multi-region, management events, log validation) + EventBridge alerts: root sign-in and IAM change in **us-east-1** (aliased provider, forwarded to eu-central-1), KMS disable/deletion regional (D24) — applied 2026-11-14, trail logging, 2 rules in us-east-1; second foundation plan clean
- [x] `docs/adr/` with the MADR template (D26) — `0000-template.md` + index of the eight
- **Learn:** state, locking, OIDC trust conditions (`sub` claim scoping)

### 1. Foundation — ≈ $2.5–3/month standing
- [x] `network` (no NAT, flow logs → S3), `kms` producer grants for the stores below (key from slice 0, D11), `dynamodb` (TTL on, epoch seconds, PITR, deletion protection), `s3`, `ecr` (+ lifecycle, scan on push) — *applied 2026-11-14/15: `network` (94 resources), then `dynamodb`, `s3` (module for the QR bucket, SSE-KMS + bucket key), `ecr` (8 repos) — 37 resources; second plan clean, TTL/PITR/deletion protection/immutability checked on the account. KMS: the QR bucket needs no key-policy statement (IAM principals with `kms:ViaService`, granted in `iam`); the service-principal grants (SNS→SQS, EventBridge, Step Functions) land with `messaging`*
- [x] `messaging`: queues, DLQs, `redrive_allow_policy`, derived visibility timeouts, `raw_message_delivery` — *applied 2026-11-15 (31 resources + the key policy): 4 queues + DLQs on the CMK, timeouts 30/360/180/120 s from the consumer formula, `purchase-confirmed` topic; the SNS→SQS key grant lives in `modules/kms`. A test publish arrived raw in the encrypted queue; second plan clean. No email subscription on the topic (Phase 1 had one): it would mail buyers' addresses to the owner*
- [x] Review fixes (2026-11-15): permissions boundary allows `iam:PassRole` only on `twr-<env>-*` roles and only to the services that run project code (D12, was `*`; applied 2026-11-15, second plan clean); `trivy config` added to `ci.yml` (D2); SSM parameter types declared, not inferred; foundation `default_tags` shared by both providers
- [x] `identity` (Cognito, deletion protection, scanner client + `ticketing/gate` scope), `iam` (permissions separate from trust, every role, confused-deputy conditions, boundary attached), Access Analyzer — *applied 2026-11-15 (70 resources; second plan clean; a client_credentials token for `ticketing/ops` minted): `modules/identity` (admin-created users only, 14-char passwords, `ops` + `gate` clients), `modules/iam` (19 roles: task + execution per ECS service incl. `migrate`, 3 Lambdas, 2 API Gateway, scheduler, saga), Access Analyzer in `modules/audit`; names of not-yet-built things decided once in foundation `locals`*. Access Analyzer's first scan: 7 findings, all intended trust (4 GitHub OIDC roles, 2 SSO roles, `OrganizationAccountAccessRole`), none on the 19 new roles; three archive rules in `modules/audit` (GitHub OIDC, Identity Center SSO, management break-glass), each filter proven against the live findings, applied 2026-11-15 (+ `apply-archive-rule` once for the existing findings): **0 Active, 7 Archived**
- [x] SES address identities; `qr-generation` verify base URL from the REST API's invoke URL (a reference, never typed) — *identities applied 2026-11-15 (`live/foundation/ses.tf`: one address, the owner's, as both sender and sandbox recipient), verified by click and a test send delivered; account in the sandbox, 200/day at 1/s; the QR base URL is wired from `module.rest_api.invoke_url`, applied 2026-11-16*
- [x] `terraform test` for messaging, dynamodb, lambda-function, kms grants (D2) — *2026-11-16: `modules/<m>/tests/*.tftest.hcl`, 21 runs, plan against a mocked provider (no AWS). kms: rotation, deletion window, the root statement, the alarm publishers' and SNS→SQS grants with their source conditions. dynamodb: TTL on its attribute, on-demand, PITR 7 d, deletion protection, AWS-owned key, key attributes exactly the keys, the `by-token` index. lambda-function: arm64 image, active tracing, declared log group with retention, ESM enabled + `ReportBatchItemFailures` + batch 10, no `maximum_concurrency`, tag or long name refused. messaging: derived visibility (360 / 120 s), CMK + 300 s reuse everywhere, redrive and `byQueue` redrive-allow, raw delivery, TLS-deny on every queue, SNS admitted only with its topic's SourceArn, bad consumers refused. Each guard proven by breaking its line (7 of 7 caught). In `ci.yml` after validate*
- [x] `lambda-function` ×3 (arm64, log groups with retention, `ReportBatchItemFailures`, ESM `enabled = true`) — *images built and pushed by hand 2026-11-15 (arm64 on an x86 host: the Dockerfiles only COPY, so no emulation), digests in `/twr/dev/images/<module>` as `images.yml` will write them (`tf-env.sh` resolves them into `TF_VAR_image_digests`); `modules/lambda-function` written and `hold-cleanup` applied 2026-11-15 (second plan clean; direct invoke on a seeded expired hold: `reclaimed=1`, seat back to AVAILABLE, logs in the declared 3-day group); `qr-generation` and `queue-notifier` applied 2026-11-16 with their ESMs (Enabled, `ReportBatchItemFailures`, batch 10); `qr-generation` direct invoke rendered a 512×512 PNG under `tickets/<uuid>.png`, SSE-KMS with bucket key. **Its first cold start hit the 10 s INIT limit** (`Init Duration: 9998.96 ms, Status: timeout`), and Lambda re-ran init inside the invoke (4.2 s), so the message still succeeded. First pull of a fresh ~120 MB image; to watch in slice 5, with SnapStart unavailable to images; ECR basic scan: 13 HIGH, all Ubuntu 26.04 base packages (pcre2, glibc, perl, apt), none in the jars*
- [x] `websocket-api` (`integration_response_key = "/200/"`), `rest-api` resources + deployment `triggers` — *applied 2026-11-16 (68 added over two applies; second plan clean). `modules/websocket-api`: `$connect`/`$disconnect` onto DynamoDB with the VTL in `templates/`, the three response pieces per route, `dev` stage on auto-deploy, `$connect` throttling (D18), access logs without the query string. `modules/rest-api`: REGIONAL, Cognito authorizer, `POST /events/{eventId}/purchase` → `StartExecution` (202 with the order id cut from the execution ARN; 409 on a retried order id; 400/502 with fixed bodies, never Step Functions' message, which carries the account id), `ANY /{proxy+}` method; its VPC_LINK integration, deployment and stage are the session stack's, redeployed on the published `definition_hash` (D5). The saga's base URL and `TICKET_VERIFY_BASE_URL` are the invoke URL computed from the fixed stage name. New: the account's API Gateway CloudWatch role (scoped to `/twr/<env>/apigw/*`, not the managed `logs:*`) and `aws_api_gateway_account`. `qr-generation` and `queue-notifier` wired with their ESMs. `UpdateAccount` refused a role scoped to the two access log groups (it checks the policy itself, not only the trust): the role carries the managed policy's action set, bounded to this account and region. Verified end to end with a real socket: handshake 220 ms; registry row with `expiresAt` 86 399 s ahead (epoch seconds); one admission on `queue-admissions` → `ADMITTED` frame 732 ms after the send; row gone on close; CONNECT / `@connections` POST / DISCONNECT all 200 in the access log*
- [x] `observability`: log groups, dashboard from `templatefile()`, saved Logs Insights queries, foundation alarms with `treat_missing_data` pinned, on the `ops-alarms` topic from slice 0 (D16) — *applied 2026-11-16 (32 added, 1 in place: the subscription's new redrive policy; second plan clean). `modules/observability`: 8 log groups (6 ECS services, metrics, saga; 3 days) + ECS Exec audit (30 days); the collector config in SSM from `templates/otel-collector-config.yaml.tftpl`; the dashboard from Phase 1's JSON as `templates/dashboard.json.tftpl` (Phase 2 names, every DLQ, a Lambda row and a queue-socket row; WAF per-rule lines wait for slice 2); 6 saved queries; 13 alarms (5 DLQ, 4 queue age at 3× visibility, 3 Lambda errors+throttles, admission publish), each `notBreaching`, alarm + OK actions, and a link into the new `docs/runbook-alarms.md`. `modules/messaging` gains the subscription DLQ D16 counts. 5 module tests, two guards proven by breaking them. The email subscription click was done in slice 0. The first apply rejected the dashboard (`flatten()` is recursive and spread each Lambda metric row into strings); fixed with `concat(...)` and a test asserting every metric row is an array, proven by putting `flatten()` back. Verified: 13 alarms `OK`; `lambda-cold-starts` run on the real logs (shows qr-generation's INIT timeout); a test message in `qr-generation-dlq` → `ALARM` in 188 s with the SNS action executed through the CMK (D11/D16), purge → `OK` in 188 s with the recovery action*
- [x] Data protection: DynamoDB PITR + deletion protection, Cognito deletion protection, ECR immutable tags (D24) — *verified live 2026-11-16, everything as D24 says: both tables deletion-protected, PITR 7 days, TTL on; user pool `ACTIVE`; all 8 repositories `IMMUTABLE` with the same lifecycle (untagged 1 day, keep 3); all 4 buckets block public access and are `BucketOwnerEnforced`; state versioned, QR not (SSE-KMS + bucket key, 7-day expiry), CloudTrail 90-day and flow logs 7-day expiry; trail multi-region with log validation, logging; the CMK `Enabled` with rotation. Pinned by module tests: new `identity` (3), `ecr` (2), `s3` (5) beside `dynamodb`; each guard proven by breaking it (7 of 7). RDS's dev-vs-production values land with the `rds` module (slice 3). Found open: the IAM-change alert has no exclusion for `tf-apply-foundation` or service-linked roles yet (D24); it fires on every IAM write, the owner's applies included*
- [x] Verify: QR + hold-cleanup by direct invoke; second plan clean; sweep TAGS tier lists only Terraform-tagged resources; Access Analyzer shows no findings — *run 2026-11-16. Direct invokes done (hold-cleanup 2026-11-15, `reclaimed=1`; QR 2026-11-16, PNG under `tickets/<uuid>.png`, SSE-KMS). Access Analyzer: **0 Active**, 7 Archived (the intended trust), the analyzer last ran after the latest applies. Sweep: BILLING clean, nothing untagged, 216 tagged resources. The sweep itself had three defects, fixed: the KMS and "DynamoDB not on-demand" checks filtered a single object (`KeyMetadata[?…]`, `Table[?…]`), which JMESPath always evaluates to null, so neither could ever fire; and its name pattern was Phase 1's, so no `twr-` name was checked for a missing tag. CMKs now report under STANDING: foundation's one is a deliberate $1/mo (D11), and a sweep that always fails stops being read. It also found `/aws/apigateway/welcome` with no retention, created by API Gateway when the account's CloudWatch role was set: declared in `live/foundation/edge.tf` (3 days, ordered before the setting) and imported; that plan is the import and nothing else, so foundation has no drift. Applied 2026-11-16 (1 imported, 1 changed); second plan clean; the one-time `import` block then removed, plan still **No changes**; a re-run sweep lists no group without retention*
- [x] **Destroy the Phase 1 resources in the management account** (`--profile default`; then `teardown-sweep.sh` there too) — checklist reviewed with the owner first (nothing wanted: images rebuilt by CI, users by `create-test-users.sh`). No longer blocking, since the `twr-dev-` names do not collide (D25) — *checklist written 2026-11-16 from a read-only inventory: [`docs/phase1-destroy-checklist.md`](phase1-destroy-checklist.md) (18 steps in dependency order, a keep list incl. Identity Center's KMS key, six owner decisions). Run 2026-11-16 with the recommendations: everything Phase 1 built is gone (6 APIs, 3 functions, 34 task definitions, 8 queues, 2 tables, the pool, 8 repositories, 8 security groups, 15 roles, 4 policies, …); kept: Identity Center's KMS key, the trail, GuardDuty, `infra/org`. Sweep clean in every region; SSO works; twr-dev plans No changes. Result table in the checklist*
- **Learn:** module boundaries, SSM as stack interface

### 2. Session edge + seat inventory — ≈ $0.13/h
- [x] **Spike D5** (VPC Link v2 for REST → internal ALB, provider argument for the target) and **D6** (is JA4 computed for a REST-stage ACL?) — *run 2026-11-17. **D5 confirmed**: the stage reaches the internal ALB through VPC Link v2 (ALB's own 404 through the public stage). **D6: JA4 is not computed** for a REST-stage ACL (no `ja4Fingerprint` or `ja3Fingerprint` in any of 46 records from two TLS clients), so the fleet guard leaves the ACL (D6, changed 2026-11-17). `Authorization` redaction verified. Foundation's part (WAF log bucket + 2 SSM parameters, 8 added) applied, second plan clean; session 9 added with both toggles on, No changes with them off. Built: `modules/alb` (internal, `drop_invalid_header_fields`, default action a fixed `404 {"error":"NO_ROUTE"}` so a response proves the ALB answered), `modules/rest-stage` (`aws_apigatewayv2_vpc_link`, `HTTP_PROXY` + `VPC_LINK` + `integration_target` = the ALB ARN, URI `http://<alb-dns>/{proxy}`, deployment on `definition_hash`, stage with X-Ray and access logs), `modules/waf` (the JA4 fleet guard alone, count mode, logs to S3 with `authorization` redacted); session toggles `enable_alb`, `enable_waf`; foundation gains the WAF log bucket (D6, changed 2026-11-17). 10 module tests, 9 guards proven by breaking them. trivy with the toggles on: one finding, AWS-0054 (HTTP listener), suppressed with D5. Run steps: [`runbook-session-edge.md`](runbook-session-edge.md)*
- [x] NAT, internal ALB, VPC link + private integration, REST stage, method throttling, WAF (buyer rules on queue join + purchase, baseline in count mode, logging to S3, D6) — *applied and checked 2026-11-17, then destroyed (sweep clean). `modules/nat`: zonal in AZ a with an EIP and a default route in each app route table; `regional` behind `nat_mode` (D4). `modules/rest-stage`: + the queue integrations and method throttling on `POST` queue join and `POST` purchase only (D6, added 2026-11-17: foundation's new `/events/{eventId}/queue` resource). `modules/waf`: the spike's JA4 rule replaced by a `buyer-entry` label rule (`POST` + a regex built from the published resource paths) and four buyer rules scoped to it: flood guard 300/min per IP, credential guard 30/min per `Authorization`, anonymous-IP, Bot Control common. The baseline (IP reputation, known bad inputs, common rule set) runs on the whole stage in `count` mode (`waf_baseline_mode`). An optional `loadtest_allow_cidr` allow rule comes first. Session toggles `enable_nat`, `nat_mode`, `waf_baseline_mode`, `loadtest_allow_cidr` (sensitive, `-var` only). 57 module test runs pass across `infra/`, 9 new guards each proven by breaking it. trivy clean with every toggle on. Foundation applied 2026-11-17 (6 added, 1 in place). Its second plan was **not** clean: `definition_hash` changed again, because the hash covers whole resource objects and a newly created method reads back with attributes it did not have at create time. One more apply converged, then two plans in a row showed No changes. Expect this once after any apply that creates a resource feeding the hash. Session: 18 added with `enable_nat`, `enable_alb` and `enable_waf` on, No changes with them off. Read back from AWS: the zonal NAT `available` in the first public subnet, an active default route to it in all three app route tables; method settings on `POST` purchase (100/200) and `POST` queue (500/1000) only; the eight rules in order. Twelve requests (curl and a browser User-Agent), checked in the WAF logs: the `buyer-entry` label on exactly the four `POST` queue and purchase requests, and none on the queue poll, `/queue/admit`, `/seats` or `/orders/o1/payment`. Bot Control (`CategoryHttpLibrary`) blocked curl on the two entry points only. The browser User-Agent reached the ALB on queue join and the Cognito authorizer (401) on purchase. `GET` on the new queue resource reaches the ALB, and `/queue/admit` still falls through to `/{proxy+}`. The count-mode baseline matched nothing: no false positives on this traffic, but the saga's own requests arrive only in slice 3, so the baseline stays in count. k6 identifies itself as an HTTP library, so the load test needs `loadtest_allow_cidr`, as D6 says*
- [x] `ecs-service` module with autoscaling; seat-inventory on ARM64 / Spot — *applied and checked 2026-11-18.*
  - **ECS Exec works on the read-only root** (D7, D9): a real `execute-command` session ran in the seat-inventory container. `/tmp` is writable, and a write to `/app` fails with `Read-only file system`. The whole session is recorded in `/twr/dev/ecs-exec`, its transcript included.
    - Exec runs as `uid=0`: the SSM agent's user, not the application's `app` (1001). It is root with every capability dropped and a read-only root filesystem.
    - The collector sidecar's agent stays `STOPPED`: that image has no shell, and nothing needs to exec into it.
  - **Built.**
    - `modules/ecs-service` holds the task definition, the service and autoscaling, and nothing else (D21): ARM64, `FARGATE_SPOT`, or the D7 mix behind `capacity`.
    - Deployments: circuit breaker with rollback, `wait_for_steady_state`, 100/200.
    - Containers: read-only root with writable `/tmp` and the two SSM agent paths, every capability dropped, `awslogs` non-blocking with a 25m buffer, `stopTimeout` 45, the ADOT sidecar.
    - Scaling: target tracking on `ALBRequestCountPerTarget`, plus optional scheduled actions.
    - `modules/alb` gains the target groups (IP targets, `/actuator/health/readiness`, 30 s deregistration delay) and the path rules (D21).
    - Session: the `twr-dev` cluster (Container Insights off, Exec sessions logged to `/twr/dev/ecs-exec`), and the toggles `services`, `image_digests`, `capacity`, `desired_count`, `task_size`.
    - 13 + 8 module test runs; 16 guards proven by breaking them.
  - **Image.** Built for arm64 by hand under QEMU (the Dockerfile has `RUN` steps, unlike the Lambdas'), digest in `/twr/dev/images/seat-inventory-service`. ECR scan: 13 HIGH, all Ubuntu base packages, none in the jars.
  - **The first apply failed, by design.** The circuit breaker stopped it: Tomcat could not create its work directory in `/tmp`, a root-owned Fargate mount (see D7, 2026-11-18). After the Dockerfile fix the re-apply replaced the tainted service.
  - **Read back from AWS.**
    - 1/1 running on `FARGATE_SPOT`, arm64, rollout `COMPLETED`, target `healthy`.
    - Ready about 55 s after container start on 0.25 vCPU: Boot 46 s, then the JWKS warm-up 4 s, with readiness after it (D7). The grace period stays 120 s.
    - Requests through the stage: the seat paths reach the service (Spring Security's `Bearer invalid_token` 401), and an unclaimed path gets `404 NO_ROUTE`.
    - The collector: X-Ray traces, and EMF `http.server.requests` in `/twr/dev/metrics`.
  - **Second plan not clean at first.** ECS stores container definitions with empty lists and `hostPort` filled in, so every plan replaced the task definition. They are now written out, and a test guards them. Second plan: **No changes**.
  - **Found, open.**
    - Spring Security 7's `WWW-Authenticate` carries `resource_metadata="http://<internal ALB DNS>/.well-known/oauth-protected-resource"`, built from the `Host` API Gateway sends, so every unauthenticated caller learns the internal ALB's name.
      - *Fixed in code 2026-11-18, in `security-starter`.* `BearerChallengeEntryPoint` removes the parameter from Spring's own challenge. `ProtectedResourceMetadataRefusal` answers the anonymous `/.well-known/oauth-protected-resource` endpoint with 404; it would name the same host.
      - Every service gets the fix with its next image. To check on the next session: a 401 through the stage is plain `Bearer`.
    - The 401 requests' traces are not joined to the edge's trace: only `security filterchain` spans arrive.
- *Moved to slice 3, 2026-11-19:* saga requests reviewed in the WAF baseline's sampled requests before switching to block (D5). The saga's own requests through the stage exist only once the saga runs, which is slice 3.
- [x] Verify: `k6/seat-race.js` through the edge → exactly one winner per seat; flow logs show DynamoDB and ECR layer traffic **not** via the NAT; ~~WAF baseline sampled requests reviewed → block~~ *(moved to slice 3, 2026-11-19, with the item above)* — *done 2026-11-18, below*
  - *Flow logs, 2026-11-18*, from the two seat-inventory applies (2664 records). Method: the NAT's own interface is the one with an address in a public subnet. Every source it received from was matched against `ip-ranges.json` and the S3 / DynamoDB prefix lists.
    - **ECR layers skip the NAT.** 798 MB came from S3 to the app subnets through the gateway endpoint, about five task starts' worth of seat-inventory layers.
    - **The collector image does not.** 262 MB reached the NAT from CloudFront addresses. That is the ADOT sidecar from `public.ecr.aws`, whose layers are served from CloudFront, where no gateway endpoint reaches. It recurs on every task start and every scale-out.
      - *Fixed in code 2026-11-18, not yet applied.* An ECR pull-through cache rule for `public.ecr.aws` (`modules/ecr`, `public_mirrors`). Its repository, `mirror-twr-dev/aws-observability/aws-otel-collector`, is created by Terraform: immutable, scanned, with the lifecycle. So no role needs `ecr:CreateRepository`. The collector-carrying execution roles may pull from it and `ecr:BatchImportUpstreamImage` into it. `ecs-service` refuses a collector image straight from `public.ecr.aws`.
      - The prefix is `mirror-<prefix>`, not `<prefix>-*`: `ci-images` may push to `<prefix>-*` (D14), and must not be able to plant a tag in the mirror before its first pull.
      - To prove on the next session's flow logs: no CloudFront bytes through the NAT, except the one first import.
    - The rest is about 1 MB of regional AWS APIs (ECR auth and manifests, logs, SSM, Cognito JWKS), the NAT's intended traffic (D3, D4).
    - **DynamoDB: no traffic to judge.** Every request was a 401 before the table was touched. It is checked after the k6 run, which writes holds.
  - *Prepared 2026-11-18 for the seat-race session* (`docs/runbook-session-edge.md` §6).
    - `load-test` reads the Phase 2 pool and clients from SSM, and the `ops` secret from Cognito at run time (`scripts/cognito-env.sh`). The operator token was minted and checked: `token_use` access, scope `ticketing/ops`.
    - `scripts/seat-holds.sh` is the table-side check of the race.
    - `infra/scripts/nat-traffic.sh` makes the flow-log check repeatable. It reproduced the numbers above.
    - The seat paths are not buyer entry, so the race needs no `loadtest_allow_cidr`.
  - **Seat race and flow logs: done, 2026-11-18** (session 20:27–20:50 UTC, destroyed, sweep clean). Image `9cb3cc4` carries the `security-starter` fix.
    - **Exactly one winner per seat.** Two runs of 200 VUs on 10 seats through the stage, VPC link and ALB: **10 / 190 / 0** won / lost / unexpected each time, `http_req_failed` 0.
      - `seat-holds.sh` after each: 10 HELD, each with its one holdId, nothing else.
      - Winners p50/p95/p99 0.91/1.71/1.96 s, then 1.50/2.22/2.33 s. Losers 4.45/6.88/6.99 s, then 3.82/5.22/5.33 s.
      - Phase 1's runs: losers 5.33/8.64/8.84 s, then 3.02/4.91/5.01 s.
      - k6's `p(95)<2000` threshold on all holds fails, as Phase 1's numbers would have: losing costs the queue in front of one 0.25 vCPU task, not the write.
    - **The time is behind the ALB.** The access log: losers 4.02 s at the stage and 4.01 s in the integration (p50), so API Gateway, the WAF and the VPC link add about 10 ms.
      - The task's 1-minute CPU maximum was 21%, which a 10-second burst can hide, so CPU saturation is not shown.
      - The SDK's default 50-connection pool queueing 200 concurrent conditional writes fits as well. To measure in slice 5's proof run (0.5 vCPU × 2 tasks, D7).
      - One task throughout: target tracking does not react to a 10-second burst.
    - **Flow logs, this session only** (`nat-traffic.sh 2026-11-18 20:25`).
      - **DynamoDB took the gateway endpoint:** 0.65 MB from it and 1.37 MB to it, on the task's interface, and none through the NAT.
      - ECR and collector layers: 212.5 MB from S3 through the endpoint.
      - **No CloudFront through the NAT at all:** the pull-through import is done by ECR itself, so even the first pull never crosses the NAT. The last session had 262 MB.
      - What is left on the NAT is regional AWS APIs (EC2 ranges, 0.4 MB in, 9.4 MB out). Most likely the telemetry exports and logs at 100% trace sampling, not checked.
    - **The fixes, live.**
      - A 401 through the stage is exactly `WWW-Authenticate: Bearer`, or Spring's RFC 6750 error for an invalid token, and names no host.
      - The collector runs from `mirror-twr-dev/…:v0.50.0`, imported by the task's own role at first pull.
      - A lifecycle preview on the mirror expires 0 images: the index protects its per-architecture children.
    - The WAF baseline review moved to slice 3 (2026-11-19): it needs the saga's own requests.
- **Learn:** private integration, SG chains, why ECR pulls go via the S3 endpoint

### 3. Data + saga — ≈ $0.20/h
- [x] RDS (private, CMK), Valkey (TLS), `migrate` profile + migration task creating databases and per-service roles (D9, D10, D13) — *Terraform half written and planned 2026-11-19, not applied. `modules/rds`: Postgres 18.6 on `db.t4g.micro`, gp3 20 GB, single AZ, storage, master secret and Database Insights (standard, 7 days) on the CMK, parameter group pinning `rds.force_ssl` and `log_lock_waits`, dev values (no backups, no final snapshot, no deletion protection) one variable each (D24). It also mints the per-service secrets `checkout_app` and `ticketing_app`, write-only from an ephemeral password, so no password reaches state. `modules/cache`: Valkey 9.1, one `cache.t4g.micro` node in the db subnets, TLS `required`, at rest on the CMK (D11 row added), no snapshots. Session toggles `enable_db` and `enable_cache`. Tests: rds 6, cache 3; trivy clean (AWS-0077 and AWS-0177 for D24, AWS-0176 for D9). Foundation applied (3 SSM parameters added; second plan clean). Session: No changes with everything off; 8 to add with both on, read attribute by attribute. The `migrate` profile was built 2026-11-19 in a new `migration-starter` module and is used by checkout and ticket-issuance. It is Boot's `FlywayMigrationStrategy`, run as master. Before Flyway it creates the database and role (the password is reset on every run), revokes `PUBLIC` connect and grants the role `CONNECT`. After Flyway it grants DML, sequence usage and default privileges, and revokes the role's access to `flyway_schema_history`. It then exits. Its test is `MigrateProfileIT` on `postgres:18-alpine`, 9 runs, covering the D10 proof list: a second run is a no-op; DML works; DROP, ALTER, CREATE and TRUNCATE are refused; CONNECT to the other database is refused; a rotated password takes effect. Each of the 6 guards was proven by breaking it. Each service has its own `MigrateProfileIT` running its real context in the profile. The real jar exits 0, 0 and then 1 with a wrong master password. The ITs moved to `postgres:18-alpine`. `modules/migration-task` was written 2026-11-19: one task definition per database (`twr-<env>-migrate-checkout`, `-ticketing`) and no service. It runs the service image by digest with `SPRING_PROFILES_ACTIVE=migrate` and `DB_URL` built from the RDS address with `sslmode=require`. `DB_USERNAME`/`DB_PASSWORD` come from the master secret's JSON keys and `MIGRATE_APP_ROLE`/`MIGRATE_APP_PASSWORD` from the service secret's. It is hardened like ecs-service: ARM64, read-only root with `/tmp`, capabilities dropped, awslogs non-blocking. There is no collector and no ECS Exec. 6 test runs, 7 breaks each caught. In the session, the cluster now exists with `enable_db` alone. A migration task is planned only for a database whose image digest is known, and a `check` warns about the rest. Plans: with everything off, only the new output (no infrastructure); with `enable_db`, 8 to add plus the warning (no checkout or ticket-issuance image pushed yet); with placeholder digests, 10 to add, both families on the `migrate` role pair. Known gap: the RDS server certificate is not verified (no CA bundle in the image). `infra/scripts/migrate.sh <checkout|ticketing>` was written 2026-11-19. It runs the family's latest revision with `run-task` on on-demand Fargate in the app subnets with the migrate SG, waits for the task to stop (10 minutes at most), and prints the log tail with the RDS host and account masked. It exits with the container's code, or 2 when the container never ran. Before starting anything it checks the cluster, the task definition and an available NAT. Its guards were checked against the empty session. **Live check passed 2026-11-19** ([`runbook-session-data.md`](runbook-session-data.md)):
- **Images and apply.** The checkout and ticket-issuance images were pushed at `b11d563` (arm64). The ECR scan on push shows 13 HIGH, all Ubuntu packages of the `eclipse-temurin:26-jre` base (glibc, pcre2, perl, apt), the same as seat-inventory's: base-image debt, not ours. Applied with `enable_nat` and `enable_db`, 15 added, Postgres in 6m37s.
- **First runs.** `migrate.sh checkout` created the database and role, applied 2 migrations to schema version 2, granted DML and exited 0 in 61 s. `migrate.sh ticketing` did the same with 1 migration, version 1, exit 0 in 78 s. Each time includes the image pull; Spring itself started in 15 s at 0.5 vCPU.
- **Second runs.** Both found the database and role, reset the password, applied 0 migrations and exited 0 (74 s and 73 s).
- **`db-probe.sh`.** On the live instance, as each role with its own secret's password, all 7 checks came out as D9 says, on both databases:
  - read and write on its own tables allowed;
  - no superuser, CREATEDB or CREATEROLE;
  - `DROP` refused (`must be owner`);
  - `CREATE TABLE` refused (`permission denied for schema public`);
  - the Flyway history refused;
  - `CONNECT` to the other database refused (`permission denied for database`).
- **Log fix.** `migrate.sh` now prints the migration-starter's lines and WARN/ERROR, not a plain tail. Hibernate's startup lines had pushed the migration out of a 30-line tail.

**Valkey live check passed 2026-11-19** (`runbook-session-data.md` §5):
- **Before the session.** waiting-room's `WaitingRoomFairnessIT` moved to `valkey/valkey:9.1-alpine`; its 14 tests pass, including the concurrent races, so the Lua scripts run unchanged on Valkey. In the session root, waiting-room joined `services`. It gets an ALB rule at priority 10 (`/events/*/queue`, `/events/*/queue/admit`, `/events/*/admissions/*`) and `REDIS_SSL=true` with the cache endpoint. A validation refuses it without `enable_cache`. Image `b11d563` (arm64) pushed.
- **Apply.** With `enable_nat`, `enable_alb`, `enable_cache` and waiting-room: 25 added. The cache took 9m9s; the service reached steady state in 1m48s.
- **FIFO through the edge,** with five Cognito test users and the operator token:
  - joins answered 201 `WAITING` at positions 1 to 5 in order;
  - admit 2 returned exactly the first two joins' tokens, in order;
  - the re-read showed buyers 1 and 2 `ADMITTED` and buyers 3 to 5 `WAITING` at positions 1 to 3;
  - the saga's `GET /admissions/{customerRef}` answered `admitted: true` for buyer 1 and `false` (`WAITING`) for buyer 3.
- **Read back.** The replication group shows transit encryption `required` and at rest on. The admissions reached SQS: the queue-notifier Lambda was invoked once for the batch. No error in waiting-room's log.
- **Found.** JDK 26 warns that Netty (Lettuce's transport) calls a restricted method (`System::loadLibrary`), which a future release will block unless native access is enabled. The fix is `--enable-native-access=ALL-UNNAMED` in waiting-room's `ENTRYPOINT`. Left for the next image.*
- [x] EventBridge connection + state machine ~~from `templatefile()`~~ *from `docs/checkout-saga.asl.json` with its placeholders replaced (2026-11-19: one file, read by the contract tests too, instead of a template copy)* — *the connection was applied and checked 2026-11-19:*
  - `modules/saga` holds `aws_cloudwatch_event_connection` `twr-<env>-saga`: OAuth client_credentials against the Cognito token endpoint, ops client, `ticketing/ops` only. The name falls under the saga role's `connection/twr-<env>-*` grant.
  - Its secret is on the AWS-owned key (D11 row added).
  - The client secret is read in the session from the ops client (`data "aws_cognito_user_pool_client"`), the one secret D13 accepts in state.
  - Session toggle `enable_saga`.
  - 4 test runs; 4 breaks each caught (grant, scope, name, method).
  - Applied alone: 1 added. `describe-connection`: `AUTHORIZED`, `POST` to the token endpoint, no KMS key, secret `events!connection/twr-dev-saga/…`. EventBridge fetched a token on create, which proves the client, the secret, the token URL and the scope.
  - *The state machine was written, applied and checked 2026-11-19.*
    - **Module.** `modules/saga` gains `aws_sfn_state_machine` `twr-<env>-checkout-saga`, STANDARD, on foundation's saga role.
    - **Definition.** It is `docs/checkout-saga.asl.json` with its three placeholders replaced: the same file the contract tests read, so the deployed saga and the tested one cannot drift. A precondition refuses a surviving placeholder.
    - **Settings.** On the CMK (D11 row added: the history holds the buyer's email). Logging is `ALL` without execution data; X-Ray is on.
    - **Foundation changes.** The saga role gains `GenerateDataKey`/`Decrypt` scoped by the `aws:states:stateMachineArn` encryption context (no `kms:ViaService`, which AWS warns can stop executions), and `GenerateDataKey` for log delivery. The key policy gains `kms:Decrypt` for `delivery.logs.amazonaws.com`, this account only (`state_machine_log_delivery`).
    - **Tests.** saga 8 runs against the real ASL, kms 7. Five breaks, each caught: AWS-owned key, execution data logged, no X-Ray, a placeholder left, a widened log-delivery grant.
    - **Plans.** Foundation: 2 in place (the key policy, the saga role's policy). Session with `enable_saga`: 1 to add.
    - **Applied.** Foundation: 2 changed, and a second plan was clean. Session: 1 added. `describe-state-machine` shows `ACTIVE`, STANDARD, `CUSTOMER_MANAGED_KMS_KEY`, logging `ALL` with `includeExecutionData` false, X-Ray on.
    - **Live check.** One execution was started directly, with a marker email and service URLs at a nonexistent API id (inside the role's `execute-api` grant). It failed closed, as designed, in 4 s: `CheckAdmission` hit `States.Http.IpError` three times (the call and its 2 retries), then `AdmissionNotVerified`. The history is readable on the CMK. All 14 events reached `/aws/vendedlogs/states/twr-dev-checkout-saga`, with `details` limited to type, name, error, cause and resource. The marker email and the payment token appear in no log event. The connection's token against real services, and the purchase and refund paths, are the slice 3 verify item.
    - **Found.** trivy scans a saved `*.tfplan` left in `infra/` as a plan snapshot, whose paths miss `.trivyignore.yaml`'s scoping, and fails. Remove plan files before scanning (CI never has them).
- [x] `infra/scripts/session.sh up`: all five services, in the D23 order, each on its own DML-only DB role — *written and planned 2026-11-19, not yet run:*
  - **Session wiring.** checkout, issuance and notification are wired into the session.
    - **Routes:** ALB rules issuance 30 (`/orders/*/tickets`, `/tickets/*/qr`) before checkout 40 (`/orders`, `/orders/*`), because an ALB `*` crosses `/`. notification has no HTTP surface.
    - **Databases:** each database service logs in from its own secret's JSON keys, with `SPRING_FLYWAY_ENABLED=false` (D10).
    - **Notification:** sends through SES sandbox, with both addresses from SSM and `TICKET_BASE_URL` set to the stage.
    - **Validation:** `services` refuses checkout or issuance without `enable_db`.
  - **The script.** `infra/scripts/session.sh up|down` runs the three stages: apply with seat-inventory, then `migrate.sh` both databases, then apply everything. It refuses to start without all five image digests (checked: it named the one missing). `down` destroys and sweeps.
  - **Image.** notification was pushed at `5b87541` (arm64).
  - **Plan.** The full end state is 57 to add; the connection and state machine already exist.
  - **Runbook.** `docs/runbook-session.md`.
  - **Run 2026-11-19 with `AUTO_APPROVE=1`, exit 0.**
    - **Stage 1:** 36 added. Postgres took 6m28s, Valkey 10m41s, seat-inventory steady in 1m58s.
    - **Stage 2:** `migrate.sh checkout` applied 2 migrations, exit 0, 63 s; `migrate.sh ticketing` applied 1, exit 0, 75 s.
    - **Stage 3:** waiting-room, issuance, checkout and notification steady in 0m56s–2m39s. `describe-services`: all five 1/1, `COMPLETED`.
    - **Found: two drifts that kept a second plan from being clean, fixed in the same session.**
      - `rds.force_ssl` is a static parameter that RDS stores as `pending-reboot`; the provider's default `immediate` re-applied it on every apply. Now `apply_method = "pending-reboot"`, with a test.
      - rest-stage's deployment trigger hashed whole integration objects. Computed attributes read back differently after a create, so the next apply replaced the deployment for nothing (foundation's `definition_hash` had the same cause in slice 2). It now hashes the integrations' arguments, with a test.
    - **After one apply under the new formula** (1 added, 1 changed, 1 destroyed: the deployment swap), the second plan with every toggle on showed No changes.
    - **IntelliJ:** "HTTP links are not secure" on the two ALB URIs, the deliberate D5 hop, is suppressed with its reason (`infra/CLAUDE.md` row added).
- [x] README *Known gaps* closed in code (2026-11-16): saga `CheckAdmission` → `NotAdmitted` before `HoldSeat`; price owned by the seat and returned by the hold (`ALL_NEW`), `amountCents` dropped from the purchase template. Foundation applied 2026-11-16 (2 in place: the purchase integration and the published `definition_hash`; second plan clean). ~~Left: verify on AWS a purchase without a pass fails `NotAdmitted` and the charge equals the seeded price~~ — **verified on AWS 2026-11-19**, on the full session through the edge. Seats A1 and A2 were seeded at 12345; two Cognito test users bought with a browser User-Agent.
  - **No pass.** Buyer 1 never queued; POST purchase answered 202. The saga entered only `CheckAdmission → IsAdmitted → NotAdmitted` and `FAILED` with `NotAdmitted`. A1 is still `AVAILABLE`, and `GET /orders/{id}` is 404: nothing was held or created.
  - **The price is the seat's.** Buyer 2 joined, was admitted, and bought A2 with a body claiming `amountCents: 1`. The hold returned `priceCents: 12345`; `CreateOrder` sent 12345; the payment was `CAPTURED`. The order reads `PAID`, `amountCents 12345 EUR`, and A2 is `SOLD`. The body's amount was ignored.
- [x] WAF baseline: the saga's requests (and the rest of a full session's traffic) reviewed in the WAF logs for baseline `count` matches, any false positive scoped out with its D-entry, then `waf_baseline_mode = "block"` (D5, D6; moved from slice 2, 2026-11-19) — *done 2026-11-20, on the verify session.*
  - **Traffic reviewed.** The WAF logs held 68 requests over the whole session. The count phase covered the happy path and both refund outcomes. Then the baseline went to `block`, and the refund drill and the happy path ran again. Every saga call back through the stage was there: the admission check, hold, create order, charge, seat confirm, refund (4) and hold release (2).
  - **Result.** All 68 were allowed by the default action. **No baseline rule matched, in either phase** (IP reputation, known bad inputs, common rule set). The only matches were the buyer side's by design: the `buyer-entry` label on the queue join and purchase, and Bot Control's `TGT_TokenAbsent` there, not blocking.
  - **No false positive to scope out.** `waf_baseline_mode` now defaults to `block`, so every `session.sh up` enforces it. `count` remains for reviewing a new kind of traffic first. The live session planned No changes with the new default.
- *Found 2026-11-19, on the known-gaps run: the confirmation email cannot reach a load-test buyer in the SES sandbox.*
  - **What ran.** Buyer 2's purchase ran the whole happy path. `issued ticket c929b4b4…` was logged under the purchase's trace; the QR Lambda ran once; `GET /tickets/{id}/qr` gave `READY` and a presigned URL.
  - **What failed.** notification's `sendEmail` was refused: `MessageRejectedException: Email address is not verified`. The recipient is the buyer's Cognito email (`lt-2@example.test`, by design: the verified claim, never the request body), and the sandbox delivers only to verified identities. The fallback recipient applies only when the event has no email. The message retries and will reach the DLQ, whose alarm emails the owner.
  - **Decided 2026-11-20: a sandbox redirect.** A test buyer with the owner's address would prove one buyer and leave the slice 7 race broken.
    - **The setting.** notification gains `notification.sandbox-recipient` (`NOTIFICATION_SANDBOX_TO`). When it is set, every confirmation goes to that verified identity. Its body opens with `[SES sandbox: addressed to <buyer>]`, so a test still shows that the right buyer was chosen.
    - **Logging.** The redirect is logged by order id, with no address in the log (D11).
    - **Separate from the fallback.** The fallback stays "no email on the event", a different meaning.
    - **Where it is set.** The session sets it from `ses/fallback_recipient`. It is dropped with SES production access (D20).
    - **Tests.** Mailer tests 6 (2 new; both fail with the redirect removed). The consumer IT passes with the setting unset.
    - **Docs.** notification's `ARCHITECTURE.md` recipient diagram was redrawn; it still showed the pre-Cognito `customerRef` rule.
    - **Image.** Pushed at `9e2b564` (arm64).
  - *Refund drill knob, 2026-11-20:* the session variable `drill_environment` merges extra environment per service, limited by an allowlist (seat-inventory `SEAT_INVENTORY_HOLD_DURATION`; checkout `PAYMENT_GATEWAY_LATENCY`, `_DECLINE_RATE`, `_ERROR_RATE`). Spring's relaxed binding maps these onto the properties. Checked: a non-drill variable is refused, and so is a drill knob on the wrong service. The refund drill's two knobs plan into the task definitions.
  - *Found 2026-11-20:* every session plan with `enable_db` off failed ("Invalid template interpolation value"). The `db_url` local interpolated the RDS address, which is null without the instance. The bug came in with the `session.sh` wiring and went unseen because every later plan had the database on. Fixed by building the URLs only with the instance. The all-off plan is healthy again (an output value only).
- [x] Verify: purchase happy path; **the refund path on AWS for the first time** (`runbook-checkout-saga.md`, *The Refund Path*); both still green with the baseline in `block` — *done 2026-11-20, one session through `session.sh up`.*
  - **Clean up.** Stage 3 was 21 added, 0 changed, 0 destroyed: the drift fixes hold.
  - **The tool.** `load-test/scripts/saga-purchase.sh` drives each run: seed, join, admit, buy through the edge, wait for the saga, then print the states, the order and the seat.
  - **Happy path (baseline counting).** All ten states, `SUCCEEDED`; order `PAID` at 12345 EUR cents; seat `SOLD`. Ticket issued, QR `READY`. notification logged the sandbox redirect for the order, then "sent purchase confirmation": no exception, queue drained.
  - **Refund drill (baseline counting).** `drill_environment` set seat-inventory's hold to `PT10S` and the gateway latency to `PT15S`: 2 task definitions replaced, 2 services rolled, nothing else.
    - `tok_ok`: `ConfirmPurchase` failed **once** with `States.Http.StatusCode.404`, entered once and not retried. Then `RefundPayment → RefundSucceeded` (`refundStatus SUCCEEDED`) `→ ReleaseSeatAfterRefund → PurchaseRefunded`. The order is `REFUNDED` and the seat `AVAILABLE`.
    - `tok_refund_error`: the same single 404. The refund answered HTTP 200 with `refundStatus FAILED`, which the Choice routed to `RefundFailed`. The order stays `PAID` for a human; the seat stays `HELD` until the cleanup Lambda reclaims the expired hold.
  - **With the baseline in `block`.** Both refund outcomes were identical. With the knobs removed (2 task definitions back), the happy path was `SUCCEEDED`, `PAID` and `SOLD` again.
  - **Not covered here.** `invariants.sh` and the dashboard's `checkout.refunds{outcome=failed}` are left for slice 5's reconciliation task and dashboard work.
  - **Also found.** The test users' password lived in a session scratchpad and was lost between sessions. It was reset with `admin-set-user-password`, and only the new value is kept, outside the repo.
- **Learn:** migrations as a deploy step, ECS Exec as the bastion

### 4. CI/CD — $0
- [ ] Infracost comment on infra PRs (API key as an Actions secret) (D17)
- [ ] `ci.yml`, `images.yml` (affected-image matrix, native arm64, digests → SSM, SBOM), `terraform.yml` (gates, `terraform test`, plan **summary** comment, gated apply)
- [ ] Every action pinned to a SHA; per-workflow `permissions`; account id masked in every job — check one public log by eye (D14)
- [ ] `session.yml` wrapping `session.sh` (D23) green from a cold session stack; a rollback by digest input exercised
- [ ] **From here on, foundation is applied only from CI** (D12); local applies stop
- [ ] `session-reaper.yml` hourly with `expires_at`; `drift.yml` nightly
- **Learn:** OIDC subject scoping, environment approvals, digest promotion

### 5. Observability + the proof — ≈ $0.30/h
- [ ] Alarms **delivered to the inbox** (not just in ALARM state) for a forced DLQ message, a forced saga failure and a stopped consumer (D16)
- [ ] Reconciliation as a one-off ECS task (private RDS, `twr-dev-` names, read-only role), wired as the last step before destroy; `load-test/README.md` updated (D16)
- [ ] Collector `detailed_metrics` → server-side p99 on the dashboard
- [ ] **WAF proof:** each buyer rule blocks targeted requests under its own label; Bot Control enforced (D6)
- [ ] **Concurrency proof:** load test with the runner allow-listed for the run (D6), 0.5 vCPU / 1 GB, `FARGATE` base 1 + Spot, scheduled scale-up before start, target tracking observed, readiness-gated ALB
- [ ] Spike: JDK AOT cache start-up, with and without, on 0.5 vCPU (D7)
- [ ] Verify: `invariants.sh` → SOLD = PAID = CAPTURED = tickets, **0 double-sold**
- **Learn:** what the second task and the second AZ do to the losers' p99

### 6. Open functional work — session-sized
- [ ] D18 spike: `TransactWriteItems` via the `$connect` service integration, refused handshake on a cancelled transaction; then the socket-ticket endpoint + race test + route throttling
- [ ] D19 verification endpoint, `ticketing/gate` scope + scanner client, atomic check-in + race test
- [ ] Step Functions X-Ray segment (retro §7)

### 7. Phase 3 seam + retro — $0
- [ ] Confirm `ecs-service` owns nothing else; IAM trust as an input; app subnets carry the EKS tags; sketch `live/eks` (D21)
- [ ] ADRs 0001–0008 complete (D26)
- [ ] `docs/phase2-retro.md`; `architecture.md` §1 + PNG updated to the built state; CLAUDE.md *Build Commands*

---

## 6. Cost Estimate (eu-central-1, re-priced 2026-11-13 from the Price List API)

| Component | Hourly while up | Notes |
|---|---|---|
| NAT Gateway + EIP | ≈ $0.057 | + $0.052/GB processed; S3/DynamoDB bypass it |
| Internal ALB | ≈ $0.03 | incl. a few LCUs |
| RDS `db.t4g.micro` | ≈ $0.019 | single AZ |
| Valkey `cache.t4g.micro` | ≈ $0.014 | |
| Fargate ARM, 0.25 vCPU / 0.5 GB | ≈ $0.011 per task (Spot ≈ $0.004) | feature slices, 5 tasks |
| Fargate ARM, 0.5 vCPU / 1 GB | ≈ $0.022 per task | proof run only, 10 tasks + autoscaling |
| WAF: ACL, ~9 rules, Bot Control | ≈ $0.033 | ≈ $24/month prorated, + $1/M Bot Control requests |
| Secrets Manager (4 secrets, incl. the EventBridge connection's) | < $0.003 | |
| **Full session** | **≈ $0.25–0.35** (proof run ≈ $0.45) | slices 1–3 run lighter through toggles |

*Re-priced 2026-11-13* (Price List API, EU (Frankfurt)): NAT $0.052/h + public IPv4 $0.005/h;
ALB $0.027/h + $0.008/LCU-h; RDS `db.t4g.micro` PostgreSQL Single-AZ $0.019/h (was $0.018);
Valkey `cache.t4g.micro` $0.0144/h; Fargate ARM $0.03725/vCPU-h + $0.00409/GB-h; WAF $5/ACL +
$1/rule + $10 Bot Control per month, prorated hourly; Secrets Manager $0.40/secret-month; KMS
$1/key-month; REST API $3.70/M requests. **No line item for a VPC link**: it is free, as D5
assumed. A regional NAT Gateway is listed at the same $0.052/h; whether that is per gateway or
per active AZ is read from the NAT pricing page before it is ever built. The totals below are
unchanged.

| Bucket | Estimate |
|---|---|
| ~40 session-hours across slices 2–6, averaging ≈ $0.22/h because most sessions are partial stacks (slice 2: edge + one service; slice 6: no WAF) | $9–11 |
| ≈ 5 of those hours as full proof runs (≈ $0.45/h) | +$1–1.5 |
| Standing ~2 months (KMS $1, alarms ≈ $0.60, GuardDuty < $1 after trial, ECR ~$0.2, S3 pennies) | $4–5 |
| **Phase 2 total** | **≈ $15–17** (budget: $20) |

**Honest reading:** with the domain deferred (D20), there is about $3–5 of margin. If §6 drifts
high anyway, the levers, in order:
1. shorter sessions (the reaper's default `expires_at` from 4 h to 2 h);
2. WAF only in the slices that prove it (2 and 5); it is a third of a full session's hourly cost;
3. the proof run on Spot with `base = 0`.

The budgets (D17) are set at $20, so the first alert comes at $16 (80%), with time to pull
those levers.

## 7. Explicitly Excluded From Phase 2

- Multi-AZ RDS, per-AZ NAT, multi-node Valkey — documented as the production values, not built
- Interface VPC endpoints (cost more than NAT at this scale)
- CodeDeploy blue/green (ECS rolling + circuit breaker is the standard default; revisit in Phase 3 with Argo Rollouts)
- CloudFront + frontend (no frontend exists yet)
- Custom domain, Route 53, ACM, SES DKIM — deferred, gaps accepted (D20)
- Multiple environments (`dev`/`prod`): one environment, toggled; the layout and naming (D25) leave room for `live/<env>/`
- AWS Config, Security Hub, Control Tower, a service mesh, multi-region — reasons in D27
- RDS Proxy, IAM database authentication — reasons in D9
- Signed / rotating QR codes, WAF Challenge/CAPTCHA at the queue — both need a client app or frontend (D19, D6)
- A Lambda authorizer on `$connect` — only as D18's fallback
