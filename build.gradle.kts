subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(26))
        }
    }

    // Security floors from gradle/libs.versions.toml: constraints, not dependencies, so a
    // module without Tomcat or Jackson (the Lambdas) gains nothing.
    val catalog = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        constraints {
            val tomcat = catalog.findVersion("tomcatSecurityFloor").get().requiredVersion
            val jackson = catalog.findVersion("jacksonSecurityFloor").get().requiredVersion
            "implementation"("org.apache.tomcat.embed:tomcat-embed-core:$tomcat") { because("security floor, see libs.versions.toml") }
            "implementation"("tools.jackson.core:jackson-core:$jackson") { because("security floor, see libs.versions.toml") }
            "implementation"("tools.jackson.core:jackson-databind:$jackson") { because("security floor, see libs.versions.toml") }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // The four HTTP services refuse to start without a JWT issuer, on purpose. Tests get a
        // placeholder the same way production gets the real one -- through the environment --
        // and it is never contacted: the decoder resolves the issuer's keys lazily, on the
        // first token, and no test presents a real one. Slice tests authenticate with
        // spring-security-test's jwt() post-processor, which bypasses decoding entirely.
        environment("COGNITO_ISSUER_URI", "https://cognito.test.invalid/")
        // The warm-up would try to reach that placeholder issuer from every @SpringBootTest
        // context and log a warning per test class. Off here; its own tests cover it.
        environment("TICKETING_SECURITY_WARM_UP", "false")
    }
}
