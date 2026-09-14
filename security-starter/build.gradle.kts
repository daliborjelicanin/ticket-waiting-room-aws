plugins {
    id("java-library")
}

// A Spring Boot starter in the Boot sense: a dependency you add and an auto-configuration
// that arrives with it. The four HTTP services each used to carry an identical
// SecurityConfig -- decoder, validators, stateless/CSRF shape -- differing only in three
// lines of path rules. This module owns the mechanism once; a service keeps only its rules.
//
// Deliberately NOT folded into :common. That module is framework-free because the Lambda
// images share it, and Spring Security on a Lambda classpath would be the same regression
// slice 2 spent its time removing.
dependencies {
    implementation(project(":common"))

    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    // `api`, because this is what a starter is for: adding this module IS adding the
    // resource server. A service must not have to name spring-security itself to use it.
    api(libs.spring.boot.starter.oauth2.resource.server)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.spring.boot.starter.test)
    // The auto-configuration is exercised through a real filter chain and a throwaway
    // controller, not by asserting on bean definitions.
    testImplementation(libs.spring.boot.starter.web)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    // The health endpoint the chain opens for the ALB is actuator's; the starter itself
    // only knows its path, so the test supplies the endpoint to prove the door works.
    testImplementation(libs.spring.boot.starter.actuator)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.bundles.testing)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
