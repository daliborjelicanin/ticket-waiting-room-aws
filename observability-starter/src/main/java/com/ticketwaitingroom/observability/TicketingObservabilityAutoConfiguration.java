package com.ticketwaitingroom.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import io.opentelemetry.instrumentation.awssdk.v2_2.AwsSdkTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * What every service needs for its traces to reach X-Ray and join up there.
 *
 * <p>Boot's own OpenTelemetry auto-configuration does the general work — the SDK, the OTLP
 * exporters, the W3C propagator, a span per HTTP request. What it does not know is that the
 * backend is X-Ray, and X-Ray has opinions three of them:
 *
 * <ul>
 *   <li><b>Trace ids carry a timestamp</b> — {@link XrayIdGenerator}. Without it every span
 *       is exported and none is accepted.</li>
 *   <li><b>The trace arrives as {@code X-Amzn-Trace-Id}</b>, not {@code traceparent}. API
 *       Gateway with tracing on, and the ALB always, stamp that header on the request; the
 *       saga's {@code http:invoke} and the SQS {@code AWSTraceHeader} attribute carry the
 *       same format. The X-Ray propagator is added <i>beside</i> Boot's W3C one — both are
 *       composed into the context propagators — so a caller speaking either is joined.</li>
 *   <li><b>Every AWS SDK call is a subsegment.</b> The interceptor below turns a
 *       {@code UpdateItem} or {@code SendMessage} into a client span with the service and
 *       operation on it, and on {@code SendMessage} writes the trace into the outgoing
 *       message so {@link SqsMessageTracing} can pick it up at the other end.</li>
 * </ul>
 *
 * <p>The SDK-facing beans are conditional on the SDK being on the classpath: a service with
 * no AWS client (checkout-payment) gets the first two points and nothing it cannot use.
 */
@AutoConfiguration(after = {OpenTelemetrySdkAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class})
public class TicketingObservabilityAutoConfiguration {

    @Bean
    SdkTracerProviderBuilderCustomizer xrayTraceIds() {
        return builder -> builder.setIdGenerator(new XrayIdGenerator());
    }

    @Bean
    TextMapPropagator awsXrayPropagator() {
        return AwsXrayPropagator.getInstance();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ExecutionInterceptor.class)
    @ConditionalOnBean(OpenTelemetry.class)
    static class AwsSdkTracing {

        /**
         * Applied by each service to its SDK client builders as
         * {@code builder.overrideConfiguration(overrides)}. One bean rather than a raw
         * interceptor so a service adds one line per client and cannot half-configure it.
         */
        @Bean
        @ConditionalOnMissingBean
        ClientOverrideConfiguration awsClientTracing(OpenTelemetry openTelemetry) {
            return ClientOverrideConfiguration.builder()
                    .addExecutionInterceptor(AwsSdkTelemetry.create(openTelemetry).createExecutionInterceptor())
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Message.class)
    @ConditionalOnBean(OpenTelemetry.class)
    static class SqsTracing {

        @Bean
        @ConditionalOnMissingBean
        SqsMessageTracing sqsMessageTracing(OpenTelemetry openTelemetry) {
            return new SqsMessageTracing(openTelemetry);
        }
    }
}
