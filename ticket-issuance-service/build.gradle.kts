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
    implementation(libs.spring.boot.starter.data.jpa)
    // Boot 4 split autoconfiguration into per-technology modules: flyway-core on its own
    // no longer triggers it, and the schema silently never migrates.
    implementation(libs.spring.boot.flyway)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(platform(libs.aws.sdk.bom))
    // Declared here rather than inherited from :common, which exposes SqsMessagePump
    // but keeps the SDK compileOnly so it never reaches the Lambda images.
    implementation(libs.aws.sdk.sqs)
    implementation(libs.aws.sdk.s3)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.bundles.testing)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
}
