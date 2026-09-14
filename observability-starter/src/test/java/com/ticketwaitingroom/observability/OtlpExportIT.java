package com.ticketwaitingroom.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole export path against a real OpenTelemetry collector in Docker: a request comes
 * in wearing API Gateway's {@code X-Amzn-Trace-Id}, and what the collector prints is the
 * same trace, an X-Ray-shaped id, this service's name, and the one metric the filter lets
 * through — over the JDK HTTP sender that replaced OkHttp. This is the test that would
 * have caught each of those being subtly wrong while every bean-level test stayed green.
 */
@SpringBootTest(
        classes = OtlpExportIT.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.application.name=probe-service",
                "management.tracing.sampling.probability=1.0",
                "management.tracing.export.otlp.enabled=true",
                "management.opentelemetry.tracing.export.schedule-delay=100ms",
                "management.otlp.metrics.export.enabled=true",
                "management.otlp.metrics.export.step=1s",
                "management.metrics.enable.all=false",
                "management.metrics.enable.probe=true"
        })
@Testcontainers
class OtlpExportIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final GenericContainer<?> COLLECTOR = new GenericContainer<>(
            DockerImageName.parse("otel/opentelemetry-collector:0.128.0"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("otel-collector.yaml"), "/etc/otelcol/config.yaml")
            .withExposedPorts(4318);

    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) {
        String otlp = "http://" + COLLECTOR.getHost() + ":" + COLLECTOR.getMappedPort(4318);
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint", () -> otlp + "/v1/traces");
        registry.add("management.otlp.metrics.export.url", () -> otlp + "/v1/metrics");
    }

    @LocalServerPort
    private int port;

    @Test
    void aRequestArrivingWithAnXrayHeaderIsExportedAsThatTraceWithThisServicesName() {
        // What API Gateway (tracing on) puts on a request: an X-Ray root and its segment.
        String xrayTraceId = "1-" + Long.toHexString(Instant.now().getEpochSecond()) + "-abcdef0123456789abcdef01";
        RestClient.create("http://localhost:" + port).get().uri("/probe")
                .header("X-Amzn-Trace-Id", "Root=" + xrayTraceId + ";Parent=53995c3f42cd8ad8;Sampled=1")
                .retrieve().toBodilessEntity();

        String otelTraceId = xrayTraceId.substring(2).replace("-", "");
        String logs = collectorLogsUntil(otelTraceId);

        assertThat(logs).contains("Trace ID       : " + otelTraceId);
        assertThat(logs).contains("Parent ID      : 53995c3f42cd8ad8");
        assertThat(logs).contains("Name           : http get /probe");
        assertThat(logs).contains("service.name: Str(probe-service)");
    }

    @Test
    void onlyEnabledMetricsLeaveAndTheirTagsTravelWithThem() {
        String logs = collectorLogsUntil("Name: probe.hits");

        assertThat(logs).contains("-> outcome: Str(ok)");
        // Boot's defaults were switched off: none of the JVM series reach the collector.
        assertThat(logs).doesNotContain("Name: jvm.memory.used");
    }

    private static String collectorLogsUntil(String marker) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        String logs;
        do {
            logs = COLLECTOR.getLogs();
            if (logs.contains(marker)) {
                return logs;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("collector never printed '" + marker + "'; last logs:\n" + logs);
    }

    @SpringBootApplication
    static class App {

        @RestController
        static class Probe {

            private final MeterRegistry registry;

            Probe(MeterRegistry registry) {
                this.registry = registry;
                // Registered up front so the metric exists from the first export step.
                registry.counter("probe.hits", "outcome", "ok").increment();
            }

            @GetMapping("/probe")
            String probe() {
                registry.counter("probe.hits", "outcome", "ok").increment();
                return "ok";
            }
        }
    }
}
