package com.ticketwaitingroom.observability;

import com.ticketwaitingroom.common.messaging.SqsMessagePump;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.contrib.awsxray.propagator.AwsXrayPropagator;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.util.List;

/**
 * Continues a trace across an SQS queue.
 *
 * <p>The producer side needs no code: the SDK interceptor from
 * {@link TicketingObservabilityAutoConfiguration} writes the current trace into the
 * message's {@code AWSTraceHeader} system attribute on every {@code SendMessage}. This is
 * the consumer side. It reads that attribute back — the same {@code X-Amzn-Trace-Id} format
 * API Gateway and ALB use on HTTP — and opens a {@code CONSUMER} span under it around the
 * handler, so in X-Ray the saga's enqueue and the issuance service's insert are one trace
 * with a queue in the middle rather than two traces that happen to share an order id.
 *
 * <p>A message with no header (one put on the queue by hand, or by a producer without the
 * interceptor) starts a fresh trace instead of failing: tracing must never be the reason
 * a ticket is not issued. For the same reason a handler's exception is recorded on the span
 * and rethrown untouched — the pump's contract that a thrown handler leaves the message on
 * the queue is not this class's to change.
 */
public final class SqsMessageTracing {

    private static final String TRACE_HEADER = "X-Amzn-Trace-Id";

    private static final AttributeKey<String> MESSAGING_SYSTEM = AttributeKey.stringKey("messaging.system");
    private static final AttributeKey<String> MESSAGING_OPERATION = AttributeKey.stringKey("messaging.operation.type");
    private static final AttributeKey<String> MESSAGING_DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    private static final AttributeKey<String> MESSAGING_MESSAGE_ID = AttributeKey.stringKey("messaging.message.id");

    /** Reads the trace header off the message the way the propagator expects to read HTTP headers. */
    private static final TextMapGetter<Message> TRACE_HEADER_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Message message) {
            return traceHeader(message) != null ? List.of(TRACE_HEADER) : List.of();
        }

        @Override
        public String get(Message message, String key) {
            return message != null && TRACE_HEADER.equalsIgnoreCase(key) ? traceHeader(message) : null;
        }

        private static String traceHeader(Message message) {
            return message.attributes().get(MessageSystemAttributeName.AWS_TRACE_HEADER);
        }
    };

    private final Tracer tracer;
    private final TextMapPropagator propagator;

    public SqsMessageTracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("ticket-waiting-room.sqs");
        this.propagator = AwsXrayPropagator.getInstance();
    }

    /** The queue's name as X-Ray should show it, from the URL a service is configured with. */
    public static String queueName(String queueUrl) {
        return queueUrl.substring(queueUrl.lastIndexOf('/') + 1);
    }

    /**
     * Wrap a pump handler so each delivery is processed inside a consumer span parented on
     * the message's trace header.
     *
     * @param queueName the queue as it should read in a trace, e.g. {@code ticket-issuance-queue}
     */
    public SqsMessagePump.MessageHandler traced(String queueName, SqsMessagePump.MessageHandler handler) {
        return message -> {
            Context parent = propagator.extract(Context.current(), message, TRACE_HEADER_GETTER);
            Span span = tracer.spanBuilder(queueName + " process")
                    .setParent(parent)
                    .setSpanKind(SpanKind.CONSUMER)
                    .setAttribute(MESSAGING_SYSTEM, "aws_sqs")
                    .setAttribute(MESSAGING_OPERATION, "process")
                    .setAttribute(MESSAGING_DESTINATION, queueName)
                    .setAttribute(MESSAGING_MESSAGE_ID, message.messageId())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                handler.handle(message);
            } catch (Exception failed) {
                span.recordException(failed);
                span.setStatus(StatusCode.ERROR);
                throw failed;
            } finally {
                span.end();
            }
        };
    }
}
