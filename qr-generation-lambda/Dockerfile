# syntax=docker/dockerfile:1

# Deliberately NOT public.ecr.aws/lambda/java:25 — this project targets JDK 26, which no
# managed Lambda runtime offers. The tradeoff is that a plain JDK image has none of the
# Lambda plumbing, so the runtime interface client (a runtimeOnly dependency, staged into
# lib/ with everything else) supplies it and the ENTRYPOINT below invokes it directly.
#
# The `-jre` tag matters more here than it did for hold-cleanup: this function renders a
# PNG through java.awt/ImageIO, which lives in the java.desktop module. A full JRE has it;
# a jlink-trimmed runtime would not, and the failure would be a NoClassDefFoundError at
# first invoke rather than at build time.
FROM eclipse-temurin:26-jre

# Lambda's conventional code root. The runtime mounts nothing here — the name is a
# convention the AWS base images follow, kept so this image reads like a normal one.
WORKDIR /var/task

# Staged by the `stageLambdaLib` Sync task: this module's jar plus its whole runtime
# classpath, flat.
COPY build/lambda/lib/ ./lib/

# Lambda ignores this and runs the function as its own unprivileged sandbox user, so it
# changes nothing on AWS. It is here so the image is non-root everywhere else too -- under
# the Runtime Interface Emulator locally, and to an image scanner -- with no account needed:
# lib/ is world-readable as copied, and nothing writes outside /tmp.
USER 1001:1001

# The wildcard classpath is expanded by the JVM, not the shell, so exec form is correct
# here — a shell-form ENTRYPOINT would hand the glob to sh and break on any lib/ rename.
#
# --enable-native-access is the JDK 26 tax for not using a managed runtime: the runtime
# interface client loads its transport via System.load, which JDK 24+ reports as a
# restricted-method call and a future JDK will block outright.
#
# -Djava.awt.headless=true because ImageIO is reached through AWT, and AWT will otherwise
# try to work out what display it has. There isn't one, and finding that out lazily costs
# time on a cold start at best.
#
# TieredStopAtLevel=1 caps JIT at C1. A batch of ten QR codes is milliseconds of work, so
# the function never reaches the point where C2's optimised code repays compiling it.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-XX:+TieredCompilation", "-XX:TieredStopAtLevel=1", "-cp", "/var/task/lib/*", "com.amazonaws.services.lambda.runtime.api.client.AWSLambda"]

# Lambda passes the handler as the image CMD, so this is the default and the console's
# "Image configuration → Command override" can point at a different handler without a
# rebuild. Format is <fully-qualified-class>::<method>.
CMD ["com.ticketwaitingroom.qrgeneration.QrGenerationHandler::handleRequest"]
