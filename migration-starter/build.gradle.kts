plugins {
    id("java-library")
}

// The `migrate` profile (D10): the same service image, run as a one-off ECS task before the
// service is updated, creates its database and login role as the master user, runs Flyway,
// grants the role DML and nothing else, and exits 0 or 1. Two services own a database
// (checkout, ticketing), and the mechanism must be identical in both, so it lives once here,
// the way security-starter and observability-starter do.
//
// Kept out of :common for the same reason they are: the Lambda images share common.
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    // `api`: the services already migrate with Flyway; this starter changes how, not whether.
    api(libs.spring.boot.flyway)
    // DataSourceProperties: where the master's URL and credentials are read from.
    implementation("org.springframework.boot:spring-boot-jdbc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.postgresql)
    testImplementation(libs.bundles.testing)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
