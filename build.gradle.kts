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

    tasks.withType<Test> {
        useJUnitPlatform()
        // The four HTTP services refuse to start without a JWT issuer, on purpose. Tests get a
        // placeholder the same way production gets the real one -- through the environment --
        // and it is never contacted: the decoder resolves the issuer's keys lazily, on the
        // first token, and no test presents a real one. Slice tests authenticate with
        // spring-security-test's jwt() post-processor, which bypasses decoding entirely.
        environment("COGNITO_ISSUER_URI", "https://cognito.test.invalid/")
    }
}
