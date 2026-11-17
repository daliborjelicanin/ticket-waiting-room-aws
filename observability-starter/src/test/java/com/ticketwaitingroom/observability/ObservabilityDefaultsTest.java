package com.ticketwaitingroom.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Through a real {@link SpringApplication}, because the point is that the defaults arrive
 * with the dependency: registered in {@code spring.factories}, applied before the context
 * exists, and beneath anything a service sets itself.
 */
class ObservabilityDefaultsTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
    }

    private static ConfigurableApplicationContext run(String... args) {
        SpringApplication application = new SpringApplication(App.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(args);
    }

    @Test
    void readinessAndLivenessGroupsExistWithoutKubernetes() {
        try (ConfigurableApplicationContext context = run()) {
            HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);

            assertThat(groups.get("readiness")).as("the ALB's readiness path (D7)").isNotNull();
            assertThat(groups.get("liveness")).isNotNull();
        }
    }

    @Test
    void shutdownIsGracefulWithThePhaseEcsStopTimeoutIsSetFrom() {
        try (ConfigurableApplicationContext context = run()) {
            assertThat(context.getEnvironment().getProperty("server.shutdown")).isEqualTo("graceful");
            assertThat(context.getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
        }
    }

    @Test
    void aServicesOwnSettingWins() {
        try (ConfigurableApplicationContext context = run(
                "--spring.lifecycle.timeout-per-shutdown-phase=10s",
                "--management.endpoint.health.probes.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("10s");
            assertThat(context.getBean(HealthEndpointGroups.class).get("readiness")).isNull();
        }
    }
}
