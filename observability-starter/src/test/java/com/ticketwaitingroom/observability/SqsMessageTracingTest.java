package com.ticketwaitingroom.observability;

import com.ticketwaitingroom.common.messaging.SqsMessagePump;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The consumer half of trace continuity across a queue, against a real OpenTelemetry SDK
 * with an in-memory exporter: what the header on the message becomes on the span.
 */
class SqsMessageTracingTest {

    private static final String ROOT = "1-68c55f12-0123456789abcdef01234567";
    private static final String PARENT = "53995c3f42cd8ad8";
    private static final String TRACE_HEADER = "Root=" + ROOT + ";Parent=" + PARENT + ";Sampled=1";
    private static final String QUEUE = "ticket-issuance-queue";

    private final InMemorySpanExporter exported = InMemorySpanExporter.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .setIdGenerator(new XrayIdGenerator())
                    .addSpanProcessor(SimpleSpanProcessor.create(exported))
                    .build())
            .build();
    private final SqsMessageTracing tracing = new SqsMessageTracing(sdk);

    @AfterEach
    void closeSdk() {
        sdk.close();
    }

    @Test
    void aDeliveryIsProcessedUnderTheTraceTheProducerWroteIntoIt() throws Exception {
        AtomicReference<String> traceIdSeenByHandler = new AtomicReference<>();

        tracing.traced(QUEUE, _ -> traceIdSeenByHandler.set(Span.current().getSpanContext().getTraceId()))
                .handle(tracedMessage());

        SpanData span = theOnlySpan();
        // X-Ray writes the trace id as 1-<8 hex seconds>-<24 hex>; OTel holds the same 32 hex.
        assertThat(span.getTraceId()).isEqualTo("68c55f120123456789abcdef01234567");
        assertThat(span.getParentSpanId()).isEqualTo(PARENT);
        assertThat(span.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(span.getName()).isEqualTo(QUEUE + " process");
        assertThat(span.getAttributes().asMap()).containsValues("aws_sqs", QUEUE, "process", "msg-1");
        assertThat(traceIdSeenByHandler).hasValue(span.getTraceId());
    }

    @Test
    void aDeliveryWithNoHeaderStartsATraceRatherThanFailing() throws Exception {
        tracing.traced(QUEUE, _ -> { }).handle(Message.builder().messageId("msg-2").build());

        SpanData span = theOnlySpan();
        assertThat(span.getParentSpanContext().isValid()).isFalse();
        assertThat(span.getTraceId()).hasSize(32);
    }

    @Test
    void aFailingHandlerMarksTheSpanAndStillThrows() {
        SqsMessagePump.MessageHandler handler = tracing.traced(QUEUE, _ -> {
            throw new IllegalStateException("database down");
        });

        assertThatThrownBy(() -> handler.handle(tracedMessage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");

        SpanData span = theOnlySpan();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getEvents()).anySatisfy(event -> assertThat(event.getName()).isEqualTo("exception"));
    }

    private static Message tracedMessage() {
        return Message.builder()
                .messageId("msg-1")
                .attributes(Map.of(MessageSystemAttributeName.AWS_TRACE_HEADER, TRACE_HEADER))
                .build();
    }

    private SpanData theOnlySpan() {
        assertThat(exported.getFinishedSpanItems()).hasSize(1);
        return exported.getFinishedSpanItems().getFirst();
    }
}
