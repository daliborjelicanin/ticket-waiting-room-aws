plugins {
    id("java")
    alias(libs.plugins.spring.boot)
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(project(":common"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.redis)

    // Admissions are announced on a queue so the WebSocket notifier Lambda can push them.
    // Unlike the Lambda modules this keeps the SDK's default HTTP stack: a long-lived
    // Fargate task pays for Apache 5 once at startup and gets connection pooling for it.
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.sqs)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.bundles.testing)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.localstack)
}
