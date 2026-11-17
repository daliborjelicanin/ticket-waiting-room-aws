package com.ticketwaitingroom.observability;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Property defaults every service gets with the dependency, at the lowest precedence, so a
 * service's own {@code application.yml} (or an environment variable) still wins.
 *
 * <ul>
 *   <li><b>Health probes.</b> {@code /actuator/health/liveness} and
 *       {@code /actuator/health/readiness}. The ALB checks readiness (D7): a task that is
 *       still starting, still warming its JWT decoder (security-starter's warm-up runs before
 *       Boot reports ready), or already draining answers 503 and gets no traffic, instead of
 *       failing requests while "healthy". It is the same contract Phase 3's Kubernetes probes
 *       read.</li>
 *   <li><b>Graceful shutdown with a 30 s phase.</b> ECS's {@code stopTimeout} (45 s) is set
 *       from the phase and must exceed it, or SIGKILL lands mid-drain.</li>
 * </ul>
 *
 * <p>All three are Boot 4's defaults already (probes stopped being Kubernetes-only in 4.0).
 * They are pinned anyway because infrastructure outside the JVM is set from them, the ALB's
 * health-check path and the task's {@code stopTimeout}, and a default that changed in a Boot
 * upgrade would break that silently (D7).
 */
public class ObservabilityDefaults implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "ticketingObservabilityDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            "management.endpoint.health.probes.enabled", "true",
            "server.shutdown", "graceful",
            "spring.lifecycle.timeout-per-shutdown-phase", "30s");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
    }
}
