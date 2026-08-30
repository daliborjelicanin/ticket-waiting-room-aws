plugins {
    id("java")
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(project(":common"))
    implementation(libs.aws.lambda.core)

    implementation(platform(libs.aws.sdk.bom))

    // See the configurations.all block below for why this has no per-dependency exclusions.
    implementation(libs.aws.sdk.dynamodb)
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

// The two HTTP stacks the AWS SDK pulls in by default — Apache 5 for sync calls, Netty for
// async, 12 jars between them — are the single biggest contributor to Lambda's separate 10s
// INIT budget, and this function uses neither: url-connection-client below is a thin wrapper
// over the JDK's own HttpURLConnection with no transitive dependencies. Slice 2 measured the
// difference at 9999 ms -> 3411 ms.
//
// Excluded at the configuration level rather than on the one dependency that happened to
// bring them. A per-dependency exclude only holds until some *other* dependency pulls them
// back in, which is exactly what happened when :common briefly exported the SQS SDK: the
// exclude on the DynamoDB/S3 dependency was still there, still correct, and completely
// bypassed. The constraint is a property of the image, so it is stated once for the module.
configurations.all {
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
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
