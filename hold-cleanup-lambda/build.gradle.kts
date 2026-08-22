plugins {
    id("java")
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(project(":common"))
    implementation(libs.aws.lambda.core)

    implementation(platform(libs.aws.sdk.bom))

    // Lambda charges cold-start time for every class it loads, and the SDK's default HTTP
    // stacks are the biggest contributor: Apache 5 for sync calls and Netty for async,
    // 15 jars between them, none of which this function needs. url-connection-client is a
    // thin wrapper over the JDK's own HttpURLConnection with no transitive dependencies.
    // Dropping the other two is what keeps initialisation inside Lambda's 10s INIT budget.
    implementation(libs.aws.sdk.dynamodb) {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.sdk.url.connection.client)

    // The runtime interface client is what lets a plain JDK base image speak Lambda's
    // runtime API — the piece the public.ecr.aws/lambda/java base images bundle and
    // eclipse-temurin does not. No source here references it (the image's ENTRYPOINT
    // names its main class), so it belongs on the runtime classpath only.
    runtimeOnly(libs.aws.lambda.ric)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
}

// Stages the deployment package as a flat directory of jars.
//
// A Lambda container image has no fat-jar step and no Spring Boot bootJar to lean on — the
// image just needs every jar on one classpath, which the Dockerfile mounts under
// /var/task/lib and the ENTRYPOINT expands with a wildcard classpath entry. Sync rather
// than Copy so a dropped dependency actually disappears from the staged output instead of
// lingering into the next image build.
//
// Line comments, not a KDoc block: Kotlin block comments nest, so a literal wildcard path
// inside one silently opens a nested comment and swallows the rest of the build script.
val stageLambdaLib = tasks.register<Sync>("stageLambdaLib") {
    group = "build"
    description = "Stages this module's jar and runtime classpath into build/lambda/lib for the Docker build."

    into(layout.buildDirectory.dir("lambda/lib"))
    from(tasks.jar)
    from(provider { configurations.runtimeClasspath.get() })
}

tasks.named("assemble") {
    dependsOn(stageLambdaLib)
}
