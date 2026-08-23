# Runbook — Docker Packaging & ECR

How a Spring Boot service in this repo becomes a Fargate-runnable image. Written while
packaging `seat-inventory-service` (slice 1, 2026-08-22); the steps are per-service and
repeat verbatim for the other four modules — substitute the module name throughout.

## Prerequisites On This Machine

```bash
export DOCKER_HOST=unix:///var/run/docker.sock   # the desktop-linux context socket is not running
export JAVA_HOME=/home/boka/.sdkman/candidates/java/26.0.1-tem
```

## Build

Gradle produces the artifact; Docker only packages it. The `.dockerignore` is a whitelist
(`**` then `!build/libs/<module>.jar`), so the build context is just the jar.

```bash
./gradlew :seat-inventory-service:bootJar
docker build -t seat-inventory-service:local seat-inventory-service
```

Result: **374 MB** local image, **140 MB** compressed in ECR.

### Dockerfile Notes

Three decisions that are easy to get wrong:

**The entrypoint is `java -jar app.jar`, not `JarLauncher`.** Boot 3.3+ replaced the old
`-Djarmode=layertools` with `-Djarmode=tools extract --layers`, which emits a *plain* jar
whose manifest has `Main-Class: <the app class>` and a standard `Class-Path: lib/…`. There
is no Spring Boot loader indirection and the `spring-boot-loader` layer comes out empty.
Most Boot-on-Docker guides still specify
`org.springframework.boot.loader.launch.JarLauncher`, which fails on this layout.

Verify the layout for any new service before trusting it:

```bash
java -Djarmode=tools -jar build/libs/<module>.jar list-layers
unzip -p build/libs/<module>.jar META-INF/MANIFEST.MF | grep -E 'Main-Class|Start-Class'
```

**One `COPY` per layer, least-changing first** (dependencies → spring-boot-loader →
snapshot-dependencies → application). A code-only rebuild then reuses the ~79-jar
dependency layer, so repeat pushes upload kilobytes rather than the whole image. The two
empty layers are harmless and populate if a SNAPSHOT dependency is ever added.

**`-XX:MaxRAMPercentage=75.0` is not optional.** Without it the JVM sizes its heap from the
*host's* total RAM; on a 0.5 GB Fargate task that means an OOM kill.

**Architecture:** this machine is `x86_64`, so the image is `linux/amd64` and matches
Fargate's default `X86_64` runtime platform. On an arm64 host, `docker build` silently
produces an arm64 image that ECS refuses to start — pass `--platform linux/amd64` there.

## Test The Image Before Pushing

Run the container against the real table. Credentials go in via a file, never `-e` flags,
so secrets stay out of the process list:

```bash
umask 077
aws configure export-credentials --format env-no-export > /tmp/aws.env
echo "AWS_REGION=eu-central-1" >> /tmp/aws.env

docker run -d --name sis-test -p 8081:8080 --env-file /tmp/aws.env seat-inventory-service:local
curl -X POST localhost:8081/events/evt-1/seats/A-3/hold     # -> 201 + holdId
curl -X POST localhost:8081/events/evt-1/seats/A-3/hold     # -> 409

docker rm -f sis-test && rm -f /tmp/aws.env
```

**Do not mount `~/.aws` into the container.** The image runs as uid 1001 and the
credentials file is mode 600 owned by uid 1000, so the SDK cannot read it — and it reports
this as `ProfileFile(sections=[])`, an *empty profile*, not a permission error. Worth
remembering: that same misleading message is what a broken ECS task role will produce.

## Push To ECR

**1. Repository** (console: ECR → Repositories → Create repository; private, mutable tags,
AES-256, tag `project=ticket-waiting-room`) or:

```bash
aws ecr create-repository --repository-name seat-inventory-service \
  --image-scanning-configuration scanOnPush=true \
  --tags Key=project,Value=ticket-waiting-room --region eu-central-1
```

**2. Authenticate.** The token lasts 12 hours, so this repeats every session:

```bash
aws ecr get-login-password --region eu-central-1 \
  | docker login --username AWS --password-stdin <account-id>.dkr.ecr.eu-central-1.amazonaws.com
```

**3. Tag and push.** Tag with the git SHA as well as `latest` — the ECS task definition
pins a tag, and a bare `latest` makes "which image is actually running" unanswerable
during a rollback.

```bash
REPO=<account-id>.dkr.ecr.eu-central-1.amazonaws.com/seat-inventory-service
SHA=$(git rev-parse --short HEAD)
docker tag seat-inventory-service:local "${REPO}:${SHA}"
docker tag seat-inventory-service:local "${REPO}:latest"
docker push "${REPO}:${SHA}"
docker push "${REPO}:latest"
```

> **zsh trap:** write `"${REPO}:latest"`, never `$REPO:latest`. Unbraced, zsh reads `:l`
> as its lowercase *modifier* and the push targets `seat-inventory-serviceatest`, which
> fails with a confusing `name unknown: The repository ... does not exist`. `:$SHA` is
> unaffected, so the bug only shows on the `latest` push.

**4. Verify:**

```bash
aws ecr describe-images --repository-name seat-inventory-service --region eu-central-1 \
  --query 'imageDetails[].{tags:imageTags,sizeMB:imageSizeInBytes,digest:imageDigest}'
```

Both tags should report the **same digest** — one manifest, two names, billed once.

## Current State

### `seat-inventory-service` (slice 1, 2026-08-22)

| | |
|---|---|
| Repository URI | `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/seat-inventory-service` |
| Tags | `8f0aa23`, `latest` (same digest) |
| Digest | `sha256:697fc7a48212ce2ec37c61a75a17e4aeab915aff8d33d0de11e67a63a948611b` |
| Stored | 140 MB compressed |
| Tag mutability | MUTABLE |
| Scan on push | **off** — not enabled at creation |
| Lifecycle policy | **none set** |

### `waiting-room-service` (slice 3, 2026-08-23)

| | |
|---|---|
| Repository URI | `<account-id>.dkr.ecr.eu-central-1.amazonaws.com/waiting-room-service` |
| Tags | `6a55391`, `latest` (same digest) |
| Digest | `sha256:1c52fcc2af92b4a16ff77daa16eb17cd03af230a766510d343d8342326da2cc4` |
| Stored | 140 MB compressed (60 dependency jars vs seat-inventory's ~79 — no AWS SDK) |
| Tag mutability | MUTABLE |
| Scan on push | **on** — the gap above, fixed at creation this time |
| Lifecycle policy | keep last 3 |

The packaging steps repeated verbatim. Three things differ about the *service*, and they
matter when the task definition is written:

- **No task role.** This service makes no AWS API calls at all — its only dependency is
  the ElastiCache endpoint, gated by security group rather than IAM. Slice 1's task role
  has no counterpart here; only the execution role (ECR pull + logs) is needed.
- **`REDIS_HOST`/`REDIS_PORT` replace `AWS_REGION`** as the task definition's environment
  variables, and they are *required* — the image carries no working default, because the
  ElastiCache endpoint does not exist until the cluster is created. That sequencing is
  real: the cache must exist before the task definition can be written.
- **`REDIS_SSL`** must be `true` if the cluster is created with encryption in transit
  enabled. Left unset it defaults to `false`. A mismatch surfaces as an opaque connection
  timeout, not a TLS error — no truststore work is needed either way, since ElastiCache
  certificates chain to Amazon Root CA and the Temurin base image already trusts it.

## Cost

ECR bills **compressed** stored bytes, not the local image size: 140 MB here, against a
500 MB-month free tier — roughly three images, and more in practice because rebuilds share
the unchanged dependency layer. Not urgent, but a lifecycle policy keeps it bounded:

```bash
aws ecr put-lifecycle-policy --repository-name seat-inventory-service --region eu-central-1 \
  --lifecycle-policy-text '{"rules":[{"rulePriority":1,"description":"keep last 3","selection":{"tagStatus":"any","countType":"imageCountMoreThan","countNumber":3},"action":{"type":"expire"}}]}'
```

## Teardown

Nothing. The repository and image stay between sessions — that is the point of pushing
once and reusing it. Slice 1's teardown covers the ECS service only.

## Next Slice Needs

ECS/Fargate is done without a health check — see `runbook-ecs-fargate.md`. But the **ALB
slice (6)** wants an HTTP health check endpoint, and this service has **no actuator
dependency** — so there is no `/actuator/health` to point a target group at. Add
`spring-boot-starter-actuator` to `seat-inventory-service/build.gradle.kts` before wiring
the load balancer, or the target group will fail health checks against a 404.
