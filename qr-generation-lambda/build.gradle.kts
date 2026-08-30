plugins {
    id("java")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.aws.lambda.core)
    // SQSEvent / SQSBatchResponse: plain POJOs, deserialised by the runtime interface
    // client's own bundled serialiser. No Jackson is added to this image by them, and none
    // is added by this module either — see QrGenerationHandler on why the message body is
    // a bare ticket id rather than JSON.
    implementation(libs.aws.lambda.events)

    // core only. MatrixToImageWriter lives in zxing's `javase` artifact, which drags in
    // jai-imageio-core; writing the BitMatrix out by hand is a dozen lines of ImageIO and
    // keeps the classpath — the dominant cold-start cost — smaller.
    implementation(libs.zxing.core)

    implementation(platform(libs.aws.sdk.bom))

    // See the configurations.all block below for why this has no per-dependency exclusions.
    implementation(libs.aws.sdk.s3)
    implementation(libs.aws.sdk.url.connection.client)

    // The piece the public.ecr.aws/lambda/java base images bundle and eclipse-temurin does
    // not. No source references it (the image's ENTRYPOINT names its main class), so it
    // belongs on the runtime classpath only.
    runtimeOnly(libs.aws.lambda.ric)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing)
    // Decoding, not encoding: the tests read the QR back out of the rendered PNG rather
    // than asserting on bytes nobody can interpret.
    testImplementation(libs.zxing.javase)
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

// Stages the deployment package as a flat directory of jars — see hold-cleanup-lambda for
// the full reasoning. Sync rather than Copy so a dropped dependency actually disappears
// from the staged output instead of lingering into the next image build.
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
