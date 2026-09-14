plugins {
    id("java")
    alias(libs.plugins.spring.boot)
}

dependencies {
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(project(":common"))
    // Makes this service an OAuth2 resource server for the Cognito pool -- decoder, validators
    // and the stateless filter chain -- leaving only this service's own path rules to write.
    implementation(project(":security-starter"))
    // Health endpoint, OTLP traces and metrics, X-Ray trace ids and header, SDK call spans.
    implementation(project(":observability-starter"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation(libs.spring.boot.starter.web)

    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.bundles.testing)

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
}
