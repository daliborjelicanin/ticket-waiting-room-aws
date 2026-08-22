# syntax=docker/dockerfile:1

# Deliberately NOT public.ecr.aws/lambda/java:25 — this project targets JDK 26, which no
# managed Lambda runtime offers. The tradeoff is that a plain JDK image has none of the
# Lambda plumbing, so the runtime interface client (a runtimeOnly dependency, staged into
# lib/ with everything else) supplies it and the ENTRYPOINT below invokes it directly.
FROM eclipse-temurin:26-jre

# Lambda's conventional code root. The runtime mounts nothing here — the name is a
# convention the AWS base images follow, kept so this image reads like a normal one.
WORKDIR /var/task

# Staged by the `stageLambdaLib` Sync task: this module's jar plus its whole runtime
# classpath, flat. One layer, because unlike the Fargate services this image is rebuilt
# rarely and has no meaningful split between fast- and slow-changing deps.
COPY build/lambda/lib/ ./lib/

# The wildcard classpath is expanded by the JVM, not the shell, so exec form is correct
# here — a shell-form ENTRYPOINT would hand the glob to sh and break on any lib/ rename.
#
# --enable-native-access is the JDK 26 tax for not using a managed runtime: the runtime
# interface client loads its transport via System.load, which JDK 24+ reports as a
# restricted-method call and a future JDK will block outright. Granting it explicitly
# silences the warning now and keeps the image working when that block lands.
#
# TieredStopAtLevel=1 caps JIT at C1. This function runs for under a second per tick, so
# it never reaches the point where C2's optimised code repays the time spent compiling it.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1", "-cp", "/var/task/lib/*", "com.amazonaws.services.lambda.runtime.api.client.AWSLambda"]

# Lambda passes the handler as the image CMD, so this is the default and the console's
# "Image configuration → Command override" can point at a different handler without a
# rebuild. Format is <fully-qualified-class>::<method>.
CMD ["com.ticketwaitingroom.holdcleanup.HoldCleanupHandler::handleRequest"]
