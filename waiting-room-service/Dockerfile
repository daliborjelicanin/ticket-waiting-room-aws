# syntax=docker/dockerfile:1

# --- extract -----------------------------------------------------------------
# Boot 4's "tools" jarmode rewrites the fat jar into a plain jar plus a lib/
# directory, split into layers by how often they change. The runtime image then
# has no nested-jar classloading at all — the manifest Class-Path does the work.
FROM eclipse-temurin:26-jre AS extract
WORKDIR /build
COPY build/libs/waiting-room-service.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# --- runtime -----------------------------------------------------------------
FROM eclipse-temurin:26-jre
WORKDIR /app

# The JVM never needs root. Redis is still reached by network rather than by IAM,
# but since slice 6 this service also announces admissions on SQS so the WebSocket
# notifier can push them — so it now needs a task role, carrying sqs:SendMessage on
# queue-admissions and nothing else.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --no-create-home app

# One COPY per layer, least-changing first. A code-only rebuild reuses the
# dependency layer and invalidates only the small application layer, so repeat
# pushes to ECR upload kilobytes instead of the whole image.
COPY --from=extract --chown=app:app /build/extracted/dependencies/ ./
COPY --from=extract --chown=app:app /build/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=app:app /build/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=app:app /build/extracted/application/ ./

USER app
EXPOSE 8080

# MaxRAMPercentage is what makes the JVM respect the Fargate task's 512 MB limit
# rather than sizing its heap from the host's total RAM.
#
# REDIS_HOST/REDIS_PORT are read by application.yml and must be supplied by the
# task definition: the image deliberately carries no working default, because the
# ElastiCache endpoint does not exist until the cluster is created. The localhost
# fallback in application.yml is for local runs only.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]