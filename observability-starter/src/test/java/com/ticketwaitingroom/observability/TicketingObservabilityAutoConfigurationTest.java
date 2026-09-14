package com.ticketwaitingroom.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What arrives with the dependency, checked through the beans Boot actually assembles
 * rather than by asserting on bean definitions: the ids the tracer mints, the headers the
 * propagators write, and the SDK hook a service applies to its clients.
 */
class TicketingObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OpenTelemetrySdkAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    TicketingObservabilityAutoConfiguration.class))
            .withPropertyValues("management.tracing.sampling.probability=1.0");

    @Test
    void tracesGetXrayShapedIds() {
        runner.run(context -> {
            SdkTracerProvider provider = context.getBean(SdkTracerProvider.class);
            Span span = provider.get("test").spanBuilder("probe").startSpan();
            span.end();

            String traceId = span.getSpanContext().getTraceId();
            long leadingSeconds = Long.parseUnsignedLong(traceId.substring(0, 8), 16);
            assertThat(leadingSeconds).isCloseTo(System.currentTimeMillis() / 1000, Offset.offset(5L));
        });
    }

    @Test
    void propagatesTheXrayHeaderBesideW3C() {
        runner.run(context -> {
            OpenTelemetry openTelemetry = context.getBean(OpenTelemetry.class);
            Span span = openTelemetry.getTracer("test").spanBuilder("probe").startSpan();
            Map<String, String> headers = new HashMap<>();
            try (var ignored = span.makeCurrent()) {
                openTelemetry.getPropagators().getTextMapPropagator()
                        .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));
            } finally {
                span.end();
            }

            String traceId = span.getSpanContext().getTraceId();
            assertThat(headers).containsKey("traceparent");
            // X-Ray's rendering of the same id: version, 8 hex seconds, 24 hex the rest.
            assertThat(headers.get("X-Amzn-Trace-Id"))
                    .startsWith("Root=1-" + traceId.substring(0, 8) + "-" + traceId.substring(8))
                    .contains("Parent=" + span.getSpanContext().getSpanId())
                    .endsWith("Sampled=1");
        });
    }

    @Test
    void offersOneSdkOverrideCarryingTheTracingInterceptor() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ClientOverrideConfiguration.class);
            assertThat(context.getBean(ClientOverrideConfiguration.class).executionInterceptors())
                    .hasSize(1)
                    .allSatisfy(interceptor -> assertThat(interceptor).isInstanceOf(ExecutionInterceptor.class));
            assertThat(context).hasSingleBean(SqsMessageTracing.class);
        });
    }

    @Test
    void sdkBeansBackOffWhenThereIsNoAwsSdk() {
        runner.withClassLoader(new FilteredClassLoader("software.amazon.awssdk"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean("awsClientTracing");
                    assertThat(context).doesNotHaveBean("sqsMessageTracing");
                });
    }
}
