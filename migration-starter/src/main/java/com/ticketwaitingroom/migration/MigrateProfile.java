package com.ticketwaitingroom.migration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * What the {@code migrate} profile means, in one place: no web server, Flyway on, an edited
 * migration fails the run (D10).
 *
 * <p>Added at the <em>highest</em> precedence, unlike observability-starter's defaults.
 * These are not defaults but the definition of the profile. The service task definition sets
 * {@code SPRING_FLYWAY_ENABLED=false} (D10: tasks never migrate), and a migration task copied
 * from it must still migrate rather than exit 0 having done nothing.
 *
 * <p>Runs after Boot's config-data processing (it is unordered, so last), which is when
 * {@code SPRING_PROFILES_ACTIVE} has been resolved into active profiles.
 */
public class MigrateProfile implements EnvironmentPostProcessor {

    public static final String NAME = "migrate";

    static final String SOURCE_NAME = "ticketingMigrateProfile";

    static final Map<String, Object> SETTINGS = Map.of(
            "spring.main.web-application-type", "none",
            "spring.flyway.enabled", "true",
            "spring.flyway.validate-on-migrate", "true");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles(NAME)) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, SETTINGS));
        }
    }
}
