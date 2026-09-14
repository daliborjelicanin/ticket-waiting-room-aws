plugins {
    id("java")
    alias(libs.plugins.spring.boot)
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(project(":common"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    // This service has no HTTP endpoint, and starter-web is still load-bearing: it supplies
    // the Jackson autoconfiguration the consumer parses with, and its embedded server is the
    // non-daemon thread that keeps the task alive — the pump's consumers are virtual threads,
    // so without it the JVM starts, subscribes and exits. Slice 6 also wants a port here for
    // the ALB's health check.
    implementation(libs.spring.boot.starter.web)
    // Health endpoint (the port above finally has something to answer with), OTLP traces and
    // metrics, SDK call spans, and the consumer-side span that continues the purchase's trace.
    // No security-starter here -- nothing to authorise -- so /actuator/health is open by
    // default rather than by a rule.
    implementation(project(":observability-starter"))

    implementation(platform(libs.aws.sdk.bom))
    // Declared here rather than inherited from :common, which exposes SqsMessagePump
    // but keeps the SDK compileOnly so it never reaches the Lambda images.
    implementation(libs.aws.sdk.sqs)
    implementation(libs.aws.sdk.sesv2)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.bundles.testing)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
}
