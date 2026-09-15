plugins {
    id("java-library")
}

// The observability counterpart of security-starter: a dependency a service adds, and an
// auto-configuration that arrives with it. Every Fargate service needs the same four
// things -- a health endpoint the ALB can probe, trace ids X-Ray will accept, the X-Ray
// trace header understood alongside W3C, and a span around every AWS SDK call -- and none
// of them is a fact about any one bounded context. Five copies would drift the same way
// five SecurityConfigs did.
//
// Kept out of :common for the same reason security-starter is: the Lambda images share
// common, and a Lambda's cold start cannot afford Spring or the OTel SDK.
dependencies {
    implementation(project(":common"))

    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    // `api`, because adding this module IS adding the health endpoint and the OTLP
    // exporters. A service must not have to name actuator or OpenTelemetry itself.
    api(libs.spring.boot.starter.actuator)
    api(libs.spring.boot.starter.opentelemetry) {
        // See the catalog note on opentelemetry-exporter-sender-jdk: the exporter picks its
        // HTTP sender by ServiceLoader, so the swap is one exclusion and one runtime jar.
        exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
    }
    runtimeOnly(libs.opentelemetry.exporter.sender.jdk)
    implementation(libs.opentelemetry.aws.xray.propagator)
    implementation(libs.opentelemetry.aws.sdk)
    // For ServerRequestObservationContext in the housekeeping predicate. Every service
    // already has spring-web through starter-web; declared here because a predicate that
    // failed with NoClassDefFoundError on its first request would be a silent one.
    implementation("org.springframework:spring-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")   // getCarrier() returns HttpServletRequest; the servlet API is Tomcat's at runtime

    // The AWS SDK is compileOnly for the same reason it is in :common. Two of the beans
    // here only make sense to a service that has an SDK client, and such a service
    // declares the SDK itself; the beans are conditional on the classes being present.
    compileOnly(platform(libs.aws.sdk.bom))
    compileOnly(libs.aws.sdk.sqs)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.opentelemetry.test)
    testImplementation(libs.opentelemetry.sdk.testing)
    testImplementation(platform(libs.aws.sdk.bom))
    testImplementation(libs.aws.sdk.sqs)
    testImplementation(libs.bundles.testing)
    // OtlpExportIT: a throwaway web app exporting to a real collector in Docker.
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
