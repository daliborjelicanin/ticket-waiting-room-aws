# Runbook — Observability: ADOT Sidecar, X-Ray, CloudWatch

Slice 7, the AWS half. The code half — `observability-starter`, one `XxxMetrics` per
service, the JWT warm-up — landed in `4262e76`, the `service` metric tag in `5e3c555`, and
the five images built from `5e3c555` are in ECR under that tag. This runbook makes the telemetry those images emit *land somewhere*: an ADOT
collector beside every service, the permissions it needs, and the log groups it writes.
Sections for X-Ray on the edge and the dashboard follow once the sidecar has run.

Prerequisites: `runbook-ecs-fargate.md` (cluster, execution role, the task-role pattern),
`runbook-ecr-packaging.md` (the images), and every service's own runbook for the task
definition it currently has. Standing cost of everything here: **$0** — task definition
revisions, IAM, SSM parameters and log groups are free; the only change to a running
session is +0.5 GB of Fargate memory per task (≈ $0.002/hour each).

## Design Decisions Made Before Touching the Console

**One collector per task, as a sidecar, not one per cluster.** The service exports to
`http://localhost:4318` and nothing else; the collector inherits the task role, so the
service's own role gains nothing it did not need. A shared collector service would mean a
service discovery name, a security group rule, and a network hop for telemetry — three
things that can fail and none that teaches anything a sidecar does not. (In the EKS phase
the same sidecar becomes a DaemonSet; the service does not change.)

**Our own collector config, shipped through SSM, not the image's default.** The ADOT image
ships `/etc/ecs/ecs-default-config.yaml` — OTLP in, `awsxray` and `awsemf` out, checked
against the repository on 2026-09-14 — and it is nearly right. Nearly: its EMF exporter
writes to namespace `ECS/AWSOTel/Application` and log group `/aws/ecs/application/metrics`
with dimension roll-ups **on**, which turns a metric with six attributes into eight
CloudWatch metrics. Ours is the same pipeline with a project namespace, a project log
group, roll-ups off, and the gRPC/X-Ray-UDP/StatsD receivers the services do not use
removed. The collector reads it from the `AOT_CONFIG_CONTENT` environment variable, which
ECS fills from a Parameter Store value at task start — the documented pattern, and the same
`valueFrom` mechanism the checkout task already uses for its database secret.

**Metrics travel as EMF logs, not `PutMetricData`.** The `awsemf` exporter writes CloudWatch
Embedded Metric Format events to a log group and CloudWatch derives the metrics from them.
So the task role needs `logs:*` on that group, not `cloudwatch:PutMetricData`; the plan
line said the latter, and the runbook corrects the plan. EMF also means every data point is
queryable in Logs Insights, which is a bonus the dashboard will use.

**The service name is a metric dimension because the code puts it there.** OTLP carries
`service.name` as a *resource* attribute, and the EMF exporter does not make resource
attributes dimensions unless told to convert all of them — which would also make
`telemetry.sdk.version` a dimension. So each service tags every meter with
`service=<spring.application.name>` (`management.metrics.tags.service`), one line of YAML,
and five services' `http.server.requests` stay five metrics. That line landed in
`5e3c555`, after the first `4262e76` push — the images were rebuilt once more for it.

**Collector failure never stops the service.** `essential: false` on the sidecar and a
`START` (not `HEALTHY`) dependency from the app container: if the collector dies the task
keeps serving and the exporters log a failed POST a minute — the same posture as the code,
where tracing is never why a ticket is not issued.

**0.25 vCPU / 1 GB, up from 0.5 GB.** The JVM sizes its heap from the container's cgroup
limit, which in a Fargate task with no container-level limit is the *task's* memory; at
512 MB the collector's ~100 MB and the JVM's 75 % would overlap. 1 GB with a 256 MB hard
limit on the collector leaves the JVM its 384 MB heap and 300 MB of headroom for both.

## Target Topology

```mermaid
flowchart LR
    subgraph task["one Fargate task · 0.25 vCPU / 1 GB"]
        APP["<service> container<br/>TELEMETRY_EXPORT_ENABLED=true<br/>OTLP_ENDPOINT=http://localhost:4318"]
        COL["aws-otel-collector container<br/>essential: false · 256 MB hard<br/>AOT_CONFIG_CONTENT ← SSM"]
        APP -->|"OTLP/HTTP · localhost:4318"| COL
    end
    SSM["SSM Parameter Store<br/>/ticket-waiting-room/otel-collector-config"] -.->|"execution role reads at start"| COL
    COL -->|"PutTraceSegments"| XR["X-Ray"]
    COL -->|"PutLogEvents (EMF)"| LG["/ticket-waiting-room/metrics<br/>→ namespace TicketWaitingRoom"]
    COL -->|"awslogs"| CL["/ecs/otel-collector"]
    TR["task role<br/>+ ticket-telemetry-export policy"] -.- COL
```

## What To Bring Up First

Nothing billable. Everything in §1–§8 is done against torn-down services: the task
definition revisions, target groups and tracing toggles wait for the next stack-up, which
creates the five services from them. The images are already pushed — `5e3c555`, all five,
2026-09-14 20:25–20:27, each verified to carry both starters and the `service` tag. If
they ever need rebuilding, the loop is in `runbook-ecr-packaging.md` §3; brace every
expansion and compare `imagePushedAt` afterwards, for the reason recorded there.

## Console Steps

All in **eu-central-1**. The order matters: the parameter, the policy and the log groups
must exist before a task definition references them, and a role must exist before it can
be picked from a dropdown.

### 1. The collector configuration (Systems Manager → Parameter Store → Create parameter)

| Field | Value |
|---|---|
| Name | `/ticket-waiting-room/otel-collector-config` |
| Description | `ADOT collector config for every Fargate service sidecar - slice 7` |
| Tier | Standard |
| Type | **String** (not SecureString — nothing secret, and SecureString would need KMS on the execution role) |
| Data type | text |
| Value | the YAML below, verbatim |
| Tags | `project` = `ticket-waiting-room` |

```yaml
extensions:
  health_check:

receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch/traces:
    timeout: 1s
    send_batch_size: 50
  batch/metrics:
    timeout: 60s

exporters:
  awsxray:
  awsemf:
    namespace: TicketWaitingRoom
    log_group_name: /ticket-waiting-room/metrics
    log_stream_name: otel-collector
    dimension_rollup_option: NoDimensionRollup

service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch/traces]
      exporters: [awsxray]
    metrics:
      receivers: [otlp]
      processors: [batch/metrics]
      exporters: [awsemf]
```

The differences from the image's default, each on purpose: only the OTLP/HTTP receiver
(the services speak nothing else); a project namespace and log group; `NoDimensionRollup`
so a series is one CloudWatch metric rather than one per attribute subset; a fixed
`log_stream_name` because the default derives one per task and a log group full of
one-shot streams from torn-down tasks is noise. `health_check` stays because the
container health check below calls it.

**CLI equivalent:**

```bash
aws ssm put-parameter --region eu-central-1 --name /ticket-waiting-room/otel-collector-config \
  --type String --tier Standard --value "file://$(pwd)/docs/otel-collector-config.yaml" \
  --tags Key=project,Value=ticket-waiting-room
```

(Keep the YAML in the repo as `docs/otel-collector-config.yaml` too — it is the one
artefact of this slice that Terraform will `file()` in Phase 2.)

### 2. Log groups (CloudWatch → Log groups → Create log group)

Two, both with **retention 1 day**, both tagged `project=ticket-waiting-room`:

| Name | Who writes it |
|---|---|
| `/ecs/otel-collector` | the sidecar's own stdout, via `awslogs` — "Everything is ready" and every export error |
| `/ticket-waiting-room/metrics` | the `awsemf` exporter — one JSON event per metric batch, which CloudWatch turns into the `TicketWaitingRoom` namespace |

Pre-creating the second one matters: the exporter would create it itself, with
**Never expire** retention, and EMF at one event per minute per service is exactly the
kind of log that quietly accrues.

```bash
for g in /ecs/otel-collector /ticket-waiting-room/metrics; do
  aws logs create-log-group --region eu-central-1 --log-group-name $g --tags project=ticket-waiting-room
  aws logs put-retention-policy --region eu-central-1 --log-group-name $g --retention-in-days 1
done
```

### 3. One managed policy for the collector's permissions (IAM → Policies → Create policy)

**JSON** tab, paste:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Traces",
      "Effect": "Allow",
      "Action": ["xray:PutTraceSegments", "xray:PutTelemetryRecords"],
      "Resource": "*"
    },
    {
      "Sid": "MetricsAsEmfLogs",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams",
        "logs:DescribeLogGroups",
        "logs:PutRetentionPolicy"
      ],
      "Resource": [
        "arn:aws:logs:eu-central-1:<account-id>:log-group:/ticket-waiting-room/metrics",
        "arn:aws:logs:eu-central-1:<account-id>:log-group:/ticket-waiting-room/metrics:*"
      ]
    }
  ]
}
```

**Next** → Policy name `ticket-telemetry-export`, description `What the ADOT sidecar needs:
X-Ray segments and EMF log events. Attached to every Fargate task role.`, tag
`project=ticket-waiting-room` → **Create policy**.

X-Ray has no resource-level permissions, hence `*`. No `logs:CreateLogGroup`: the group
exists from step 2, and a role that cannot create groups cannot create one with the wrong
retention. No `cloudwatch:PutMetricData`: EMF does not use it.

**Attach it to the four existing task roles** — IAM → Roles → *role* → **Add permissions ▾
→ Attach policies** → tick `ticket-telemetry-export` → **Add permissions**:

- `seat-inventory-task-role`
- `ticket-waiting-room-task-role`
- `ticket-issuance-task-role`
- `notification-task-role`

### 4. A task role for checkout, which never had one (IAM → Roles → Create role)

`checkout-payment-service` makes no AWS API calls, so it has run without a task role since
slice 4. Its sidecar does. Same recipe as `runbook-ecs-fargate.md`:

| Step | Value |
|---|---|
| Trusted entity type | AWS service |
| Use case | **Elastic Container Service → Elastic Container Service Task** |
| Permissions | tick `ticket-telemetry-export` only |
| Role name | `ticket-checkout-task-role` |
| Description | `checkout-payment-service task role: nothing for the service itself, telemetry export for its sidecar` |
| Tags | `project` = `ticket-waiting-room` |

```bash
aws iam create-role --role-name ticket-checkout-task-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"ecs-tasks.amazonaws.com"},"Action":"sts:AssumeRole"}]}' \
  --tags Key=project,Value=ticket-waiting-room
for r in ticket-checkout-task-role seat-inventory-task-role ticket-waiting-room-task-role ticket-issuance-task-role notification-task-role; do
  aws iam attach-role-policy --role-name $r --policy-arn arn:aws:iam::<account-id>:policy/ticket-telemetry-export
done
```

### 5. Let the execution role read the parameter (IAM → Roles → `ticket-ecs-execution-role`)

The **execution** role, not the task role: it is ECS itself that resolves `valueFrom` before
the container starts, the same way it already resolves the RDS secret for checkout and
issuance. **Add permissions ▾ → Create inline policy → JSON**:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "ssm:GetParameters",
    "Resource": "arn:aws:ssm:eu-central-1:<account-id>:parameter/ticket-waiting-room/otel-collector-config"
  }]
}
```

Name `ReadCollectorConfig` → **Create policy**. It sits beside the existing
`ReadDatabaseSecrets`. (`AmazonECSTaskExecutionRolePolicy`, already attached, covers the
`awslogs` writes to `/ecs/otel-collector`.)

### 6. Five task definition revisions (ECS → Task definitions)

The same edit five times. Everything referenced below exists after §1–§5; the image tag to
pin is **`5e3c555`** (pushed 2026-09-14 20:25–20:27, the build that carries the `service`
metric tag). Do **not** use the console's own *Monitoring → Use trace collection / Use
metric collection* toggles: they inject an ADOT sidecar with the image's default config,
which is the generic-namespace, roll-ups-on setup §1 replaces. Leave both unticked.

**6.0 Open the family.** ECS → left nav **Task definitions** → click the family name
(e.g. `seat-inventory-service`) → tick the **latest** revision (`seat-inventory-service:4`,
`waiting-room-service:3`, `checkout-payment-service:6`, `ticket-issuance-service:4`,
`notification-service:2` as of 2026-09-14) → **Create new revision ▾ → Create new
revision**. The form opens pre-filled from that revision; change only what follows.

**6.1 Infrastructure requirements** (the first collapsible section)

| Field | Value |
|---|---|
| Launch type | AWS Fargate (unchanged) |
| Operating system/Architecture | Linux/X86_64 (unchanged) |
| Task size → CPU | .25 vCPU (unchanged) |
| Task size → **Memory** | **1 GB** |
| Task role | unchanged for four; **checkout-payment-service → `ticket-checkout-task-role`** (the dropdown lists roles trusting `ecs-tasks`; it appears because §4 created it) |
| Task execution role | `ticket-ecs-execution-role` (unchanged) |

**6.2 Container - 1** (the service; expand it)

| Field | Value |
|---|---|
| Name | unchanged (`seat-inventory-service` …) |
| Image URI | `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/<service>:5e3c555` — replace the `@sha256:…` digest the previous revision pinned; that digest is the image *without* the starter |
| Essential container | Yes (unchanged) |
| Port mappings | `8080` TCP, HTTP (unchanged) |
| Resource allocation limits → Memory hard limit | **768** (MiB). New. The JVM's `MaxRAMPercentage=75` reads the container's limit; without one it reads the task's 1 GB and would size a heap that overlaps the sidecar's |
| Environment variables → **Add environment variable** | Key `TELEMETRY_EXPORT_ENABLED`, Value type **Value**, Value `true` |
| → **Add environment variable** | Key `OTLP_ENDPOINT`, Value type **Value**, Value `http://localhost:4318` |
| everything already there | leave: `COGNITO_ISSUER_URI`, `AWS_REGION`, queue URLs, `DB_URL`, the two `ValueFrom` secrets on checkout and issuance, the `awslogs` block |
| Startup dependency ordering → **Add container dependency** | Container name `aws-otel-collector` (type it — the dropdown only lists containers that exist when the form was opened, and the sidecar is added below), Condition **Start** |

`Start`, not `Healthy` or `Success`: the service must not wait on, or die with, its
telemetry.

**6.3 + Add container** (the sidecar)

| Field | Value |
|---|---|
| Name | `aws-otel-collector` |
| Image URI | `public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0` — the newest tag on 2026-09-14; never `latest` |
| Essential container | **No** |
| Port mappings | none. Delete the empty row the form offers; the service reaches it on the task's loopback |
| Resource allocation limits → CPU | `0` (share what is left) |
| → Memory hard limit | **256** |
| → Memory soft limit | **128** |
| Environment variables → Add | Key `AOT_CONFIG_CONTENT`, Value type **ValueFrom**, Value `arn:aws:ssm:eu-central-1:<account-id>:parameter/ticket-waiting-room/otel-collector-config` |
| Logging → **Use log collection** | ticked · `awslogs` · Add key/value: `awslogs-group` = `/ecs/otel-collector` · `awslogs-region` = `eu-central-1` · `awslogs-stream-prefix` = **the service's name** (e.g. `seat-inventory-service`) |
| | Untick **Create log group** (`awslogs-create-group`) if the console added it — the group exists from §2 and the execution role may not create groups |
| HealthCheck → Command | `CMD,/healthcheck` (the console splits on commas) · Interval `10` · Timeout `5` · Start period `10` · Retries `3` |
| Startup dependency ordering | none |

`/healthcheck` is a binary in the ADOT image that calls the `health_check` extension the
config enables. The `ValueFrom` type on a plain environment variable is what the console
offers for "a parameter or a secret"; in the stored JSON it lands under `secrets`, which is
why the *execution* role got the permission in §5.

**6.4 Storage, Monitoring, Tags** — Storage: ephemeral 21 GiB, unchanged. **Monitoring:
both toggles off** (see the note at the top of §6). Tags: `project=ticket-waiting-room`
(carried over; confirm it is there).

**6.5 Create.** The page shows the new revision (`seat-inventory-service:5` …). Open its
**JSON** tab and read three things back before moving on: `"memory": "1024"`, the sidecar's
`"secrets": [{"name": "AOT_CONFIG_CONTENT", …}]`, and the service container's
`"dependsOn": [{"containerName": "aws-otel-collector", "condition": "START"}]`. Then repeat
6.0–6.5 for the other four families.

Write the five new revision numbers into the table in *Results* below; the next stack-up's
service creation points at them.

**JSON alternative.** *Create new revision with JSON* takes the previous revision's JSON
with these edits. The sidecar block to paste into `containerDefinitions`, and the fields to
change on the service container:

```json
{
  "name": "aws-otel-collector",
  "image": "public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0",
  "essential": false,
  "cpu": 0,
  "memory": 256,
  "memoryReservation": 128,
  "secrets": [
    { "name": "AOT_CONFIG_CONTENT",
      "valueFrom": "arn:aws:ssm:eu-central-1:<account-id>:parameter/ticket-waiting-room/otel-collector-config" }
  ],
  "healthCheck": {
    "command": ["CMD", "/healthcheck"],
    "interval": 10, "timeout": 5, "startPeriod": 10, "retries": 3
  },
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/otel-collector",
      "awslogs-region": "eu-central-1",
      "awslogs-stream-prefix": "<service-name>"
    }
  }
}
```

```jsonc
// on the service container:
"image": "<account-id>.dkr.ecr.eu-central-1.amazonaws.com/<service>:5e3c555",
"memory": 768,
"environment": [ /* existing… */,
  { "name": "TELEMETRY_EXPORT_ENABLED", "value": "true" },
  { "name": "OTLP_ENDPOINT", "value": "http://localhost:4318" } ],
"dependsOn": [ { "containerName": "aws-otel-collector", "condition": "START" } ]
// at the top level:
"memory": "1024",
"taskRoleArn": "arn:aws:iam::<account-id>:role/ticket-checkout-task-role"   // checkout only
```

**CLI equivalent** — derive each new revision from the current one with `jq`, so nothing
the previous runbooks set is retyped:

```bash
SHA=5e3c555; REG=<account-id>.dkr.ecr.eu-central-1.amazonaws.com
PARAM=arn:aws:ssm:eu-central-1:<account-id>:parameter/ticket-waiting-room/otel-collector-config
for s in seat-inventory-service waiting-room-service checkout-payment-service ticket-issuance-service notification-service; do
  aws ecs describe-task-definition --region eu-central-1 --task-definition $s --query taskDefinition \
  | jq --arg img "$REG/$s:$SHA" --arg s "$s" --arg param "$PARAM" '
      del(.taskDefinitionArn,.revision,.status,.requiresAttributes,.compatibilities,.registeredAt,.registeredBy)
      | .memory = "1024"
      | (if $s == "checkout-payment-service" then .taskRoleArn = "arn:aws:iam::<account-id>:role/ticket-checkout-task-role" else . end)
      | .containerDefinitions[0].image = $img
      | .containerDefinitions[0].memory = 768
      | .containerDefinitions[0].environment += [{name:"TELEMETRY_EXPORT_ENABLED",value:"true"},{name:"OTLP_ENDPOINT",value:"http://localhost:4318"}]
      | .containerDefinitions[0].dependsOn = [{containerName:"aws-otel-collector",condition:"START"}]
      | .containerDefinitions += [{
          name:"aws-otel-collector", image:"public.ecr.aws/aws-observability/aws-otel-collector:v0.50.0",
          essential:false, cpu:0, memory:256, memoryReservation:128,
          secrets:[{name:"AOT_CONFIG_CONTENT",valueFrom:$param}],
          healthCheck:{command:["CMD","/healthcheck"],interval:10,timeout:5,startPeriod:10,retries:3},
          logConfiguration:{logDriver:"awslogs",options:{"awslogs-group":"/ecs/otel-collector","awslogs-region":"eu-central-1","awslogs-stream-prefix":$s}}
        }]' > /tmp/td-$s.json
  aws ecs register-task-definition --region eu-central-1 --cli-input-json file:///tmp/td-$s.json \
    --query 'taskDefinition.[family,revision]' --output text
done
```

### 7. Target groups → `/actuator/health` (EC2 → Target groups)

The four target groups survive teardown (the ALB does not), so this is done now, at $0,
and is in force the moment the next ALB attaches them. For each of `seat-inventory-tg`,
`waiting-room-tg`, `checkout-tg`, `ticket-issuance-tg`: open it → **Health checks** tab →
**Edit**:

| Field | Was | Now |
|---|---|---|
| Health check path | `/` | **`/actuator/health`** |
| Advanced → Success codes | `200-499` | **`200`** |
| everything else | 10 s interval, 2/2 thresholds, 5 s timeout, traffic port | unchanged |

**Save changes.** The old matcher accepted the 401 that `GET /` produced as "alive", which
proved Tomcat was up and nothing more (`runbook-alb-apigateway.md`, health-check decision).
`/actuator/health` is the one path the security chain opens anonymously
(`security-starter`), answers `{"status":"UP"}`, and a service that boots into a broken
state — no datasource, no Redis — now reports it. Bring the ALB up with a revision *older*
than §6 and its target goes unhealthy on 404: that is the check working, not a regression.

```bash
for tg in seat-inventory-tg waiting-room-tg checkout-tg ticket-issuance-tg; do
  ARN=$(aws elbv2 describe-target-groups --region eu-central-1 --names $tg --query 'TargetGroups[0].TargetGroupArn' --output text)
  aws elbv2 modify-target-group --region eu-central-1 --target-group-arn $ARN \
    --health-check-path /actuator/health --matcher HttpCode=200
done
aws elbv2 describe-target-groups --region eu-central-1 --query 'TargetGroups[].[TargetGroupName,HealthCheckPath,Matcher.HttpCode]' --output text
```

### 8. Tracing on the edge, the saga and the Lambdas

All three are toggles, all free, all reversible, all done now against torn-down services.

**8.1 API Gateway stage** — API Gateway → APIs → `ticket-edge` → **Stages → dev** →
**Logs and tracing** panel → **Edit** → **X-Ray tracing: on** → **Save changes**. From
now on every request through the stage gets an `X-Amzn-Trace-Id` with `Root`, `Parent` and
`Sampled`; the ALB forwards it, the service's X-Ray propagator joins it, and the API
Gateway segment is the root the service's segment hangs from. (`tracingEnabled` was
`false` on 2026-09-14.) No IAM: API Gateway writes its own segments.

**8.2 Step Functions** — Step Functions → State machines → `checkout-saga` → **Edit** →
the **Config** tab (gear icon at the top of the editor) → **Tracing → Enable X-Ray
tracing** → **Save** — this creates a new state machine *version* with the same definition.
The console offers to add `xray:PutTraceSegments`, `xray:PutTelemetryRecords`,
`xray:GetSamplingRules`, `xray:GetSamplingTargets` to `ticket-saga-role`; accept, or add
that inline policy by hand first. With tracing on, Step Functions emits a segment per
execution and one per state, and propagates the trace to its Lambda, SQS and SNS
integrations. Whether it forwards `X-Amzn-Trace-Id` on an `http:invoke` call is the open
question this slice answers; §9 claim 4 below reads it off the first purchase.

**8.3 Lambdas** — for each of `hold-cleanup`, `qr-generation`, `ticket-queue-notifier`:
Lambda → Functions → the function → **Configuration** tab → **Monitoring and operations
tools** → **Edit** → **AWS X-Ray → Active tracing: on** → **Save**. The console attaches
`AWSXRayDaemonWriteAccess` to the function's role if it is missing (it is, on all three).
The functions are not OTel-instrumented — a DI-free Lambda stays that way — so what X-Ray
shows is the Lambda service's own segment: init duration, invocation, the SQS source for
`qr-generation` and the notifier. That is enough to see the QR render hanging off the
purchase's trace, which is the point.

```bash
aws apigateway update-stage --region eu-central-1 --rest-api-id <rest-api-id> --stage-name dev \
  --patch-operations op=replace,path=/tracingEnabled,value=true
aws iam put-role-policy --role-name ticket-saga-role --policy-name XRay --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["xray:PutTraceSegments","xray:PutTelemetryRecords","xray:GetSamplingRules","xray:GetSamplingTargets"],"Resource":"*"}]}'
aws stepfunctions update-state-machine --region eu-central-1 \
  --state-machine-arn arn:aws:states:eu-central-1:<account-id>:stateMachine:checkout-saga \
  --tracing-configuration enabled=true
for f in hold-cleanup qr-generation ticket-queue-notifier; do
  ROLE=$(aws lambda get-function-configuration --region eu-central-1 --function-name $f --query 'Role' --output text | awk -F/ '{print $NF}')
  aws iam attach-role-policy --role-name $ROLE --policy-arn arn:aws:iam::aws:policy/AWSXRayDaemonWriteAccess
  aws lambda update-function-configuration --region eu-central-1 --function-name $f --tracing-config Mode=Active --query 'TracingConfig.Mode' --output text
done
```

### 9. Bring the stack up and read it back

The next stack-up follows each service's own runbook — ALB, RDS, ElastiCache, then the
five services — with the §6 revision numbers in place of the old ones. Bring
**seat-inventory-service** up first: it needs only DynamoDB, and every claim below except
the last can be made against it alone. Every numbered line is a claim; *Results* is where
each becomes a number.

```bash
# 1. the sidecar started, read its config from SSM, and is ready
aws logs tail /ecs/otel-collector --region eu-central-1 --since 5m | grep -E "Everything is ready|error|invalid|AccessDenied"

# 2. the service booted, warmed the decoder, and is exporting without complaint
aws logs tail /ecs/seat-inventory-service --region eu-central-1 --since 5m \
  | grep -E "JWT decoder warm|JWT warm-up|Started|Failed to export|Connection refused"
#    → "JWT decoder warm: issuer metadata and keys fetched in N ms" — N against slice 6's 12.8 s

# 3. EMF is arriving (two export steps, ~2 min), and the log-writing half of the policy works
aws logs tail /ticket-waiting-room/metrics --region eu-central-1 --since 3m | head -c 600
#    → {"_aws":{"CloudWatchMetrics":[{"Namespace":"TicketWaitingRoom","Dimensions":[["service", …

# 4. CloudWatch has a namespace with our dimensions
aws cloudwatch list-metrics --region eu-central-1 --namespace TicketWaitingRoom \
  --query 'Metrics[].[MetricName, join(`,`, Dimensions[].Name)]' --output text | sort -u
#    → http.server.requests   error,exception,method,outcome,service,status,uri
#    → seats.remaining        event,service                       (after the first hold, ≤ 60 s)

# 5. the ALB reads the new health check: target healthy on /actuator/health, not on a 401
aws elbv2 describe-target-health --region eu-central-1 --target-group-arn <seat-inventory-tg arn> \
  --query 'TargetHealthDescriptions[].TargetHealth.State' --output text          # healthy

# 6. one request through the edge = one trace: APIGW segment → seat-inventory-service → DynamoDB
curl … POST /admin/events/evt-o/seats  (ops token)  ;  curl … POST …/seats/O-1/hold  (ops token)
aws xray get-trace-summaries --region eu-central-1 --start-time $(date -u -d '-10 min' +%s) --end-time $(date -u +%s) \
  --query 'TraceSummaries[].[Id, Duration, Http.HttpURL, join(`,`, ServiceIds[].Name)]' --output text
```

Then in the console — **CloudWatch → X-Ray traces → Trace map**: three nodes,
`ticket-edge/dev` → `seat-inventory-service` → `DynamoDB`, and under **Traces** the hold
request with a `DynamoDB.UpdateItem` subsegment. A second hold on the same seat shows the
same shape with the subsegment in **fault** (the 400 `ConditionalCheckFailed`) — the race
loss, visible.

With the whole stack up, one purchase (`runbook-cognito-auth.md` §11 claim 5) is the last
claim: **one trace** from `ticket-edge/dev` through `checkout-saga`'s states, the
`http:invoke` calls into seat-inventory and checkout, the SQS hop to
`ticket-issuance-service`, and `qr-generation` — or, if `http:invoke` does not forward the
header, a saga trace and per-service traces that share an order id, which is recorded as
the finding and not fixed. Either way `ticket-issuance-queue process` appears as a consumer
span parented on the saga's `SendMessage`; that half is the code's, not Step Functions'.

## Results, 2026-09-14 — steps 1–8

Done by CLI from the repo, before any task definition exists to use them. Zero billable
resources created.

| Step | Resource | Check |
|---|---|---|
| 1 | `/ticket-waiting-room/otel-collector-config`, String, version 1 | `get-parameter` value diffs against `docs/otel-collector-config.yaml` only in the trailing newline SSM normalizes |
| 2 | `/ecs/otel-collector`, `/ticket-waiting-room/metrics` | both retention **1 day** |
| 3 | `ticket-telemetry-export` | attached to `seat-inventory-task-role`, `ticket-waiting-room-task-role`, `ticket-issuance-task-role`, `notification-task-role` |
| 4 | `ticket-checkout-task-role` | trust `ecs-tasks.amazonaws.com`; only `ticket-telemetry-export` attached |
| 5 | `ReadCollectorConfig` inline on `ticket-ecs-execution-role` | beside `ReadDatabaseSecrets` |

**Step 6, by the CLI block above** — each revision derived from the previous one with
`jq`, so every queue URL, secret and log block came across untouched (checked on checkout:
`DB_USERNAME`/`DB_PASSWORD` still `valueFrom` secrets beside the two new plain variables):

| Family | Old → new | Task role |
|---|---|---|
| `seat-inventory-service` | 4 → **5** | `seat-inventory-task-role` |
| `waiting-room-service` | 3 → **4** | `ticket-waiting-room-task-role` |
| `checkout-payment-service` | 6 → **7** | **`ticket-checkout-task-role`** — its first |
| `ticket-issuance-service` | 4 → **5** | `ticket-issuance-task-role` |
| `notification-service` | 2 → **3** | `notification-task-role` |

Read back on all five: task `memory=1024`, app container `image=<service>:5e3c555`,
`memory=768`, `TELEMETRY_EXPORT_ENABLED=true`, `OTLP_ENDPOINT=http://localhost:4318`,
`dependsOn=aws-otel-collector:START`; sidecar `essential=false`, `256/128`,
`AOT_CONFIG_CONTENT` under `secrets`, health `CMD /healthcheck`, logs
`/ecs/otel-collector/<service>`. Tagged `project=ticket-waiting-room` — the earlier
revisions never were, which the tag sweep had not noticed because task definitions are free.
**These are the revision numbers the next stack-up's service creation uses.**

**Step 7** — `checkout-tg` by hand in the console, the other three by the CLI block; all
four now `/actuator/health` with matcher `200`, interval 10 s, timeout 5 s, thresholds 2/2,
deregistration delay 30 s — only the path and the matcher changed. No ALB exists to attach
them to yet, which is the point of doing it now: in force the moment the next one does.

**Step 8** — mixed console and CLI. `ticket-edge` stage `dev`: `tracingEnabled` **true**
(console). `checkout-saga`: tracing **enabled** at 21:29, `XRay` inline policy on
`ticket-saga-role` beside `SagaInvoke` (CLI). Lambdas, all three **Active**:
`hold-cleanup` through the console, which attached a generated
`AWSLambdaTracerAccessExecutionRole-462ff057-…` customer policy (the two `xray:Put*`
actions — same grant as the managed one, under a name only the console would choose);
`qr-generation` and `ticket-queue-notifier` through the CLI with the AWS-managed
`AWSXRayDaemonWriteAccess`. Functionally identical; recorded because a tag sweep will one
day ask what that generated policy is. Nothing here bills until something runs.

Policy simulation (`iam simulate-principal-policy`) on `ticket-checkout-task-role`:
`xray:PutTraceSegments` and `xray:PutTelemetryRecords` **allowed**;
`cloudwatch:PutMetricData` **implicitDeny**, which is the design. `ssm:GetParameters` on the
parameter from `ticket-ecs-execution-role`: **allowed**.

**The simulator cannot vouch for the logs statement.** It answers `implicitDeny` for
`logs:PutLogEvents` on the EMF stream ARN — and gives the same answer for
`ticket-ecs-execution-role`, whose `AmazonECSTaskExecutionRolePolicy` grants it on `*` and
which writes every task's log stream every session. Whatever the simulator does with a
log-group ARN containing `/`, it is not evaluating the policy as CloudWatch Logs will. The
policy's resource matches the group's real ARN form exactly
(`…:log-group:/ticket-waiting-room/metrics:*`, per `describe-log-groups`); the proof is
§7 claim 3, when the first task's collector writes an EMF event or reports
`AccessDeniedException` in `/ecs/otel-collector`. Recorded so the next person does not
spend twenty minutes on the simulator.

## Results, 2026-09-14 — §9 with seat-inventory alone

`seat-inventory-service:5` on FARGATE_SPOT, 0.25 vCPU / 1 GB, public IP, no ALB, from
19:45 UTC. Both containers `RUNNING`, the sidecar **HEALTHY** within the first minute —
so the `valueFrom` on `AOT_CONFIG_CONTENT` and the execution role's `ssm:GetParameters`
work. Every numbered claim from §9, as it came out:

| # | Claim | Result |
|---|---|---|
| 1 | Collector ready | `Everything is ready. Begin running and processing data.` at 19:45:43; no `error`/`AccessDenied` at any point — the logs statement the IAM simulator would not evaluate works |
| 2 | Service up, warmed, exporting | `Started … in 45.7 s` (0.25 vCPU); **`JWT decoder warm: issuer metadata and keys fetched in 5802 ms`** (6311 ms on the second task). First authenticated request after that: **4.3 s** (3.8 s on the second task) against slice 6's 12.8 s — the JWKS half of the cost is now paid before the port opens, the rest is the JIT meeting the security filter chain and Jackson. Health from outside: `200 {"status":"UP"}` |
| 3 | EMF arriving | first stream in `/ticket-waiting-room/metrics` ~75 s after start; events carry `"Namespace":"TicketWaitingRoom"` and `"Dimensions":[["service", …]]` |
| 4 | Namespace with our dimensions | `http.server.requests{error,exception,method,outcome,service,status,uri}`, `seat.hold.attempts{outcome,service}`, `seat.hold.releases{outcome,service}`, `seat.purchase.confirmations{outcome,service}`, `seats.remaining{event,service}` — the `service` tag became a dimension exactly as intended |
| 6 | The race, in X-Ray | **30 concurrent holds on one seat → 1×201, 29×409**, 30 traces. A loser's tree: `seat-inventory-service` → `security filterchain before` (`authenticate bearertoken` 108 ms, `authorize request` 197 ms) → `secured request` → **`DynamoDB UpdateItem fault=true`** 409 ms. The conditional-write loss is a faulted subsegment under the request that lost. CloudWatch `Sum` of `seat.hold.attempts{unavailable}` over the window: **29** |

Claim 5 (target healthy on `/actuator/health`) waits for the ALB.

**Two defects found by claim 4, both fixed in the same session:**

- **`seat-inventory-task-role` had no `dynamodb:Query`.** The slice-1 policy was
  `GetItem`/`PutItem`/`UpdateItem` on the table, and the new `countAvailable` is a Query.
  The gauge did what its javadoc promises — `WARN could not count remaining seats … keeping
  the last reading`, with the trace id on the log line, and nothing else affected — which is
  the first time the least-privilege role has refused the service something and the first
  time the graceful path ran outside a test. Fixed by adding `dynamodb:Query` to the inline
  `Policy` (same table ARN); the next refresh succeeded with no restart, and
  `seats.remaining{evt-p}` read **3** (4 seeded, 1 held, 1 released back). Update the
  seat-inventory runbook's policy table.
- **The EMF exporter swallowed the race.** Micrometer exports cumulative counters; `awsemf`
  converts them to per-step deltas and, by default, uses the first observation as a
  baseline and emits nothing for it. A 20-way race whose increments all land in one step
  therefore exported as `seat.hold.attempts = 0` forever after. Fixed with
  `retain_initial_value_of_delta_metric: true` in the collector config — SSM parameter
  **version 2**, `docs/otel-collector-config.yaml` updated — and a forced redeployment to
  make the sidecar reread it (rollout 6.5 min on Spot; the old task drained cleanly). The
  first step after the fix carried `held 2 / unavailable 29 / released 1 / not_found 1`.

**Observations, not defects:**

- The `@Scheduled` gauge refresh gets its own trace every 30 s
  (`task seatInventoryMetrics.refreshRemaining`, 14 of the first 41 traces). Boot observes
  scheduled tasks by default; switch that observation off in code so X-Ray shows requests,
  not housekeeping. Follow-up for the next code change.
- X-Ray segments carry the URL but not the HTTP status (`Http.HttpStatus` empty in
  summaries, no `http.response` on the segment). The OTLP span has it; the exporter's
  attribute mapping evidently does not pick up the name Boot's server observation uses.
  Cosmetic for now — the fault flag on the DynamoDB subsegment is what carries the story.
- At first export the JVM prints `sun.misc.Unsafe::arrayBaseOffset has been called by
  com.google.protobuf.UnsafeUtil` — protobuf-java 4.34 under JDK 26, from the OTLP
  exporter. Harmless until a JDK removes the method; nothing to do here.
- The sidecar logs a deprecation notice about `DimensionRollupOption`'s future default.
  Ours is set explicitly; ignore.

Cost of the session so far: one Spot task with 1 GB for ~45 minutes, ≈ $0.01.

## Results, 2026-09-15 — §9 with the whole stack

Stack-up by CLI from the runbooks, 17:12–17:32 UTC: RDS, ElastiCache and the ALB started in
parallel (all three available in ~3 min — RDS `db.t4g.micro` is far quicker than the 6–10
min the slice-4 runbook allowed for), EventBridge connection recreated and the saga
redefined with its ARN (tracing kept on), listener + five header-guarded rules, `albDns`
stage variable, `ticketing` database, **two more revisions** (`checkout:8`, `issuance:6`)
because the RDS-managed secret ARN is minted fresh each cycle, then five services. Bring-up
of a service behind the ALB to *healthy*: ~2 min; boot times on 0.25 vCPU 47–84 s.

| # | Claim | Result |
|---|---|---|
| 1–2 | Five collectors ready, five warm-ups | `Everything is ready` ×5; warm-up `fetched in` **2694 / 4199 / 4922 / 13602 ms** (checkout, issuance, seat-inventory, waiting-room — the last on an 80 s boot); no `Failed to export` anywhere |
| 5 | Targets healthy on `/actuator/health` | all four `healthy` with the `200` matcher, within the 120 s grace |
| 6 | The race through the edge | **30 concurrent holds via APIGW → ALB → Fargate → DynamoDB → 1×201, 29×409**; seed 3.7 s (first authenticated request), a hold 0.39 s; `seat.hold.attempts{unavailable}` Sum **30** for the window (1 + 29) |
| 7 | **One purchase, one trace** | saga `SUCCEEDED` in ~5 s; order `PAID` under bob's `sub`; one ticket; seat `SOLD`, TTL removed; QR rendered. The execution's `traceHeader` is API Gateway's `Root=1-6aa97f3f-…`, and **X-Ray holds the purchase as one trace of 18 segments**: `ticket-edge/dev` → (StartExecution) → for each `http:invoke` a *second* `ticket-edge/dev` segment → `seat-inventory-service` / `checkout-payment-service` with the security filter chain and the `DynamoDB UpdateItem` subsegments → `SQS SendMessage` → **`ticket-issuance-queue process`** (the consumer span, parented on the saga's send) → its own `SendMessage` to the QR queue → and, via SNS with raw delivery, **`purchase-confirmed-notifications process`** ×3 with `SesV2 SendEmail fault=true` |

**`http:invoke` forwards `X-Amzn-Trace-Id`.** That was the open question and the answer is
yes: every call the saga makes re-enters the stage carrying the purchase's root, and the
service joins it. The trace's own shape says so — four `ticket-edge/dev` segments inside one
trace, one per saga call, each with the ALB URL as its downstream.

**Every counter in the namespace matched the traffic**: `seat.hold.attempts{held}` 3
(T-1, the saga's T-2, the T-3 winner) / `{unavailable}` 30, `seat.purchase.confirmations`
1, `waiting_room.joins{WAITING}` 1, `waiting_room.admissions` 1, `checkout.orders{created}`
1, `checkout.charges{captured}` 1, `tickets.issued{issued}` 1,
`notifications.sent{failed}` 3.

**Found, not defects:**

- **The three failed sends are the SES sandbox.** The purchase ran as `bob@example.test`
  (a pure test identity — "alice" turned out to be the owner's real mailbox, left alone),
  and the sandbox refuses unverified recipients: `MessageRejectedException: Email address
  is not verified`. Three receives, then the DLQ, purged at teardown. The redeliveries each
  opened a consumer span under the *same* trace, which is why the trace grew from 16 to 18
  segments and to 128 s: at-least-once delivery, drawn.
- **Step Functions emitted no segments of its own.** `tracingConfiguration.enabled` true,
  the four `xray:*` actions on `ticket-saga-role`, the execution carrying a sampled header —
  and no `checkout-saga` node in the service graph ten minutes later, only the
  `STEPFUNCTIONS` placeholder from API Gateway's `StartExecution`. Propagation *through* the
  saga works regardless (above). Open item for the next session: confirm whether Standard
  workflows need something beyond the toggle here, or whether the segments were simply late.
- **The QR Lambda is a separate trace, linked.** `qr-generation` ran (init 1529 ms, 1433 ms)
  under its own `TraceId 1-6aa97f47-…`: Lambda *links* an SQS-triggered invocation to the
  message's trace rather than parenting on it. Expected, and the Lambda node still shows in
  the service graph.
- **Health checks and housekeeping dominate the trace count.** Two ALB nodes probing every
  10 s at 100 % sampling produced ~200 traces per service in 20 minutes, plus a trace per
  30 s gauge refresh (`task seatInventoryMetrics.refreshRemaining`,
  `task waitingRoomMetrics.refreshDepths`). Both to switch off in code — an observation
  predicate excluding `/actuator/**`, and the scheduled-task observation — before the load
  test, where they would be noise in the p99 too.
- **`alice`'s runbook password was not the real one** (the console user was created with a
  different value on the day); `bob`'s was reset to the runbook's with
  `admin-set-user-password`, the command the runbook itself uses.

**Teardown 17:32–17:36 UTC**: five services (`--force`), the ALB, RDS
(`--skip-final-snapshot --delete-automated-backups`), ElastiCache, the EventBridge
connection and its managed secret, DLQ purged; sweep afterwards 0 across the board;
superseded images (`4262e76`, `fd39c8b`) deleted from ECR so each repository holds
`5e3c555` only. Stack up ~20 minutes: ALB $0.013, RDS $0.006, cache $0.006, five Spot tasks
with 1 GB ≈ $0.02, X-Ray/Logs pennies — **≈ $0.05 for the session**.

## Traps Predicted

- **`ValueFrom` lands under `secrets`, and the execution role is the one that needs the
  permission.** A task that stops before the collector logs anything, with
  `ResourceInitializationError: unable to pull secrets or registry auth … ssm:GetParameters`,
  is step 5 missing — the same shape as slice 4's Secrets Manager failure.
- **The collector logs `Everything is ready` and nothing arrives.** Check the service's log
  for `Failed to export` — with `TELEMETRY_EXPORT_ENABLED` unset the exporters are off and
  the service is silent about it, by design.
- **X-Ray shows nothing while metrics flow.** The traces pipeline drops spans whose trace id
  timestamp is off; the starter's `XrayIdGenerator` prevents that, but a service built from
  the pre-`4262e76` image (the digest the old revisions pinned) has no such generator. The
  image tag on the new revision is the check.
- **Task memory.** If the task is OOM-killed with two containers, the sidecar's hard limit
  did its job and the JVM was the one over; `MaxRAMPercentage=75` of 1 GB is 768 MB *if the
  container has no limit of its own* — give the service container a hard limit of 768 and
  the JVM sizes from that.
- **`EMF` events but an empty namespace.** Metrics appear in CloudWatch a minute or two after
  the log event; `list-metrics` is eventually consistent for far longer. Two export steps
  before deciding it is broken.
- **Old revisions still exist and still work** — they just export nothing. A service brought
  up from the previous runbook's revision number will look healthy and be invisible.

## Session B — the dashboard

Laptop-only: **nothing needs to be running.** A CloudWatch dashboard is built against
metric names and dimensions, and the `TicketWaitingRoom` namespace has every meter from
the 2026-09-15 session with `service`, `outcome`, `event` and `uri` as dimensions.
CloudWatch keeps the data — 1-minute resolution for 15 days, the metric definitions listable
for two weeks — so the time picker on **2026-09-15 17:10–17:40 UTC** shows real numbers:
the 30-way race, the purchase, the queue going 1 → 0, the three refused mails. The WAF
metrics from 2026-09-13 are there too. Fresh data comes with the load test, which is the
session that produces the README's chart; this one produces the board it is read from.
Cost: **$0** — the first three dashboards in an account are free, and this is the first.

### What each widget answers, and why it is shaped that way

Read the facts of the namespace first (`aws cloudwatch list-metrics --namespace
TicketWaitingRoom`), because they dictate the widgets:

- **Counters are per-minute deltas, not running totals.** The EMF exporter converts
  Micrometer's cumulative counters (`retain_initial_value_of_delta_metric: true`, §1).
  So every counter widget uses `Sum`; `Maximum` on a counter is meaningless here.
- **Gauges are gauges.** `waiting_room.queue.depth`, `waiting_room.admitted`,
  `seats.remaining` — `Maximum`/`Minimum` over the minute.
- **A timer arrives as two metrics.** `http.server.requests` carries a statistic set per
  step — `Count` and `Sum` are real, but `Min`/`Max` inside it are **0** (Micrometer's OTLP
  histogram → EMF loses them) — and `http.server.requests.max` is a separate gauge. So the
  honest latency widget is *average* (`SUM(Sum)/SUM(SampleCount)`) plus the `.max` gauge.
  **There is no p99 yet**, and the console cannot compute one from what is exported. A
  percentile needs the exporter to send bucketed histograms
  (`management.otlp.metrics.export.histogram-flavor` + `percentiles-histogram` for the
  request timer), which the EMF exporter turns into a `Values`/`Counts` distribution that
  CloudWatch *can* take `p99` of. That is a code change for the load-test session, where
  p99 is the point; this dashboard's latency widget is built so that swapping `avg` for
  `p99` is one word once the histograms flow.
- **Every dimension must be named.** A CloudWatch series is keyed by its full dimension
  set, so `http.server.requests` with seven dimensions cannot be charted "per service" by
  naming `service` alone. `SEARCH('{TicketWaitingRoom,error,exception,method,outcome,service,status,uri} MetricName="…" uri="…"', 'Sum', 60)`
  is the idiom: the schema in braces is the exact dimension set, then filters, then the
  statistic; `SUM(...)`/`MAX(...)` over the search collapses the rest.
- **Health probes are two-thirds of the requests.** Two ALB nodes every 10 s, per service.
  The latency widget therefore searches by endpoint (`uri="/events/{eventId}/seats/{seatId}/hold"`
  and so on) rather than by service; there is no `!=` in SEARCH.
- **AWS-side cross-checks beside our numbers.** `AWS/DynamoDB ConditionalCheckFailedRequests`
  on the table is the contention seen from DynamoDB's side and should track
  `seat.hold.attempts{unavailable}` one-for-one — the first time the project's own metric is
  checked against the platform's. `AWS/States` for the saga, `AWS/SQS` depth on the four
  DLQs ("must stay at zero"), `AWS/WAFV2` per rule.

The board (`docs/dashboard-ticket-waiting-room.json`, 12 widgets, 24 columns):

| Row | Widget | Metrics | Stat |
|---|---|---|---|
| 0 | header text | what the numbers mean, one paragraph | — |
| 1 | Queue depth + admitted per event | `waiting_room.queue.depth`, `waiting_room.admitted` via SEARCH on `{event,service}` | Maximum |
| 1 | Seats remaining per event | `seats.remaining` | Minimum |
| 2 | **Hold contention** (stacked) | `seat.hold.attempts{held}` green, `{unavailable}` red; right axis `AWS/DynamoDB ConditionalCheckFailedRequests` | Sum |
| 2 | Seat transitions | confirmations `confirmed`/`not_found`, releases `released`/`not_found` | Sum |
| 3 | **Conversion** (number) | `IF(admissions > 0, 100 * captured / admissions, 0)` over the last hour | metric math |
| 3 | Purchase funnel (numbers + sparklines) | joins, admissions, orders, captured, declined, tickets, duplicate deliveries, mails sent/failed | Sum |
| 4 | Charge outcomes (stacked) | `checkout.charges` by `outcome` via SEARCH | Sum |
| 4 | Latency per endpoint | avg for hold / charge / queue join from `Sum`/`SampleCount`; `http.server.requests.max` for hold | math |
| 5 | Saga executions | `AWS/States` succeeded, failed, `ExecutionTime` p90 | Sum / p90 |
| 5 | DLQs | `ApproximateNumberOfMessagesVisible` on the four DLQs | Maximum |
| 5 | WAF | `AllowedRequests` ALL, `BlockedRequests` per rule | Sum |

### Console steps (CloudWatch → Dashboards)

**1. Create the shell.** CloudWatch → left nav **Dashboards** → **Create dashboard** →
name `ticket-waiting-room` → **Create dashboard**. The *Add widget* dialog opens; **close
it** — the widgets come from the JSON.

**2. Paste the source.** On the empty dashboard: **Actions ▾ → View/edit source**. Replace
the `{"widgets": []}` in the editor with the full contents of
`docs/dashboard-ticket-waiting-room.json` → **Update** → **Save dashboard** (top right; the
dashboard is *not* saved until this is clicked, and navigating away discards it).

**3. Point the time picker at the data.** Top right: **Custom → Absolute** → 2026-09-15
17:10 to 17:40 **UTC** (the picker defaults to local time; the session was 19:12–19:36 CEST).
Every widget fills. What to see, widget by widget:

| Widget | Expected on 2026-09-15 |
|---|---|
| Queue depth | `evt-t` rises to 1 at 17:24, falls to 0 on the admit; `admitted` 1 |
| Seats remaining | `evt-t`: 3 → 2 (T-1 held) → 1 (T-2 sold) → 0 (T-3 held) |
| Hold contention | one minute with `held` 1 / `unavailable` 29 at 17:28, and DynamoDB's `ConditionalCheckFailedRequests` **29** in the same minute |
| Conversion | **100 %** (1 captured / 1 admission) |
| Funnel | 1 / 1 / 1 / 1 / 0 / 1 / 0 / 0 / 3 |
| Charge outcomes | one `captured` |
| Latency | hold avg ≈ 16–40 ms, max ≈ 400 ms (the losers' 409s); charge avg ≈ 700 ms |
| Saga | 1 succeeded, ~5 s |
| DLQs | notifications-dlq 0 → 1 at ~17:31 (purged at teardown) |
| WAF | empty in this window — switch to 2026-09-13 for the 983-block minute |

If a widget is empty with the picker right, open it (**⋮ → Edit**) and read the error
banner: a SEARCH with a wrong dimension set says `No metrics found`, which is the schema
in braces not matching `list-metrics` exactly.

**4. Read one number against the other.** The contention widget's right axis is the
check: DynamoDB counted 29 `ConditionalCheckFailedRequests` in the minute the service
counted 29 `unavailable`. Two independent sources, one number. Screenshot it for the README.

**5. Export it back to the repo** — the console is a place to look, the JSON in git is
the source of truth and Phase 2's `aws_cloudwatch_dashboard`:

```bash
aws cloudwatch get-dashboard --region eu-central-1 --dashboard-name ticket-waiting-room \
  --query DashboardBody --output text | python3 -m json.tool > docs/dashboard-ticket-waiting-room.json
git diff --stat docs/dashboard-ticket-waiting-room.json    # layout tweaks made in the console come back here
```

**CLI equivalent of steps 1–2:**

```bash
aws cloudwatch put-dashboard --region eu-central-1 --dashboard-name ticket-waiting-room \
  --dashboard-body file://docs/dashboard-ticket-waiting-room.json
# an empty DashboardValidationMessages list is the success signal; anything else names the widget
```

### Results, 2026-09-15

Built by hand in the console from the JSON above; the first attempt viewed correctly and
saved as `{"widgets":[]}` — the *Update*-without-*Save dashboard* trap step 2 warns about,
caught by `get-dashboard` showing a 241-byte body. Second attempt saved: **12 widgets,
10 160 bytes, byte-identical to `docs/dashboard-ticket-waiting-room.json`**, so the repo file
is the source of truth without an export step. Read against 17:10–17:40 UTC with nothing
running; the contention widget shows the 29 `unavailable` and DynamoDB's 29
`ConditionalCheckFailedRequests` in the same minute. The account's first dashboard: $0.

### Follow-ups this session surfaces for the code

- **Percentiles**: export the request timer as a histogram so `p99` exists in CloudWatch
  (see the timer note above). Same change makes `checkout.gateway.latency` p99-able.
- **Health-probe and scheduled-task traces** (from the 2026-09-15 results): an observation
  predicate excluding `/actuator/**` and switching off scheduled-task observation, so X-Ray
  and `http.server.requests` carry requests, not housekeeping. Without this the load test's
  p99 is diluted by ~12 probes a minute per service.
- Both ride along with the k6 work; neither needs an AWS session to verify.

## Teardown

**Done 2026-09-14 ~20:30 UTC** after the seat-inventory-only run: desired count 0, service
deleted with `--force`, cluster at 0 tasks within seconds. Sweep afterwards: no ALB, RDS,
ElastiCache, WAF or NAT; `hold-seat-schedule` disabled; 0 services. ECS tasks for the
session ≈ 45 min on Spot ≈ $0.01, plus a few cents of X-Ray and Logs ingestion. What was
left standing is what this runbook created on purpose, all $0: the parameter (v2), the
two log groups with 1-day retention, the policy, the checkout role, the five revisions,
the target-group and tracing settings.

Nothing in this runbook bills while idle. Task definition revisions, the managed policy,
the checkout task role, the SSM parameter and the two log groups all stay; the log groups
expire their own contents after a day. The session-only resources are the same five
services, ALB, RDS and ElastiCache as before, torn down per their runbooks. The one thing
to remember at teardown is the same as every session: the services, not the revisions.

## Notes for Phase 2 (Terraform)

- The collector YAML becomes `aws_ssm_parameter` with `value = file("otel-collector-config.yaml")`;
  the sidecar block becomes a `locals` template merged into each `aws_ecs_task_definition`'s
  `container_definitions`, so five families share one definition of it.
- `ticket-telemetry-export` is one `aws_iam_policy` and five `aws_iam_role_policy_attachment`s.
- The `service` metric tag is in each service's `application.yml`; nothing in Terraform
  needs to know it exists, which is the point of putting it there.
