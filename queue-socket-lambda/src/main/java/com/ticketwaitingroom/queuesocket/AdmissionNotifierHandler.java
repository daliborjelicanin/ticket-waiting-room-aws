package com.ticketwaitingroom.queuesocket;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ticketwaitingroom.common.queue.QueueAdmissionMessage;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.protocols.jsoncore.JsonWriter;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns an admission into a WebSocket frame, driven by an SQS event source mapping on
 * {@code queue-admissions}.
 *
 * <p><strong>Why a Lambda on a queue, and not a call from the waiting room itself.</strong>
 * {@code admit.lua} pops tokens from Redis and the pop is final; if the push were inline,
 * a slow or failing {@code postToConnection} would be holding up an admission that has
 * already happened, and a retry of the admit call would pop <em>more</em> tokens. The queue
 * moves the push into its own failure domain, with its own retries and its own DLQ, and
 * leaves the admit path as fast as it was.
 *
 * <p><strong>Nothing here parses JSON.</strong> The body is the bare token and the other
 * two values ride as SQS message attributes — see {@link QueueAdmissionMessage}. The
 * outgoing frame is serialised with the JSON writer the AWS SDK already loads for its own
 * wire protocol, so correctness costs neither a dependency nor a class load.
 *
 * <p>This push is the one hop in the system that is not idempotent in the usual sense: a
 * redelivered message sends the frame again. That is deliberate and harmless — an
 * {@code ADMITTED} frame carries no state transition, only news of one, so a client that
 * receives it twice learns the same thing twice.
 */
public class AdmissionNotifierHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final String TABLE_NAME_ENV = "CONNECTION_TABLE_NAME";
    private static final String ENDPOINT_ENV = "WEBSOCKET_ENDPOINT";

    private final ConnectionRegistry registry;
    private final ConnectionPusher pusher;

    /** Lambda's entry point: constructed once per execution environment. */
    public AdmissionNotifierHandler() {
        this(new ConnectionRegistry(SharedDynamo.INSTANCE, requiredEnv(TABLE_NAME_ENV)),
                new ApiGatewayConnectionPusher(ApiGatewayManagementApiClient.builder()
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        // Not optional: the management API's default endpoint is the
                        // control plane. Posting a frame requires the API's own
                        // https://{api-id}.execute-api.{region}.amazonaws.com/{stage}.
                        .endpointOverride(URI.create(requiredEnv(ENDPOINT_ENV)))
                        .build()));
    }

    AdmissionNotifierHandler(ConnectionRegistry registry, ConnectionPusher pusher) {
        this.registry = registry;
        this.pusher = pusher;
    }

    /**
     * Notify every record in the batch, reporting per-record failures rather than throwing.
     *
     * <p>Throwing would fail the whole batch and SQS would redeliver all ten messages, nine
     * of which succeeded — so one unreachable token would drag its batch-mates towards the
     * DLQ. The partial response avoids that, but <strong>only if the event source mapping
     * has {@code ReportBatchItemFailures} enabled</strong>: without it Lambda ignores this
     * return value and treats the invocation as a blanket success, silently dropping the
     * failures instead.
     */
    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage record : event.getRecords()) {
            String token = record.getBody() == null ? "" : record.getBody().trim();
            try {
                int delivered = notify(record, token, context);
                log(context, "admitted token=%s connections=%d".formatted(token, delivered));
            } catch (RuntimeException failed) {
                // Reported, not rethrown: this record alone goes back to the queue.
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(record.getMessageId())
                        .build());
                log(context, "failed push for token=%s messageId=%s — %s: %s".formatted(
                        token, record.getMessageId(), failed.getClass().getSimpleName(), failed.getMessage()));
            }
        }

        log(context, "admission notify complete records=%d failures=%d"
                .formatted(event.getRecords().size(), failures.size()));
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    /**
     * @return how many sockets actually received the frame; zero is a success, not a
     *         failure — a token with no open socket is the ordinary case for anyone who
     *         closed the tab, and the REST poll is what serves them
     */
    private int notify(SQSEvent.SQSMessage record, String token, Context context) {
        String eventId = attribute(record, QueueAdmissionMessage.ATTR_EVENT_ID);
        String admissionExpiresAt = attribute(record, QueueAdmissionMessage.ATTR_ADMISSION_EXPIRES_AT);
        String frame = admittedFrame(eventId, token, admissionExpiresAt);

        int delivered = 0;
        for (String connectionId : registry.connectionsFor(eventId, token)) {
            try {
                pusher.push(connectionId, frame);
                delivered++;
            } catch (GoneException closed) {
                // A 410 is not a delivery failure, it is news: the socket closed without a
                // $disconnect ever firing. Prune the row and carry on — retrying the record
                // would only re-discover the same dead connection.
                registry.remove(connectionId);
                log(context, "pruned closed connection=%s token=%s".formatted(connectionId, token));
            }
        }
        return delivered;
    }

    /**
     * The frame, serialised rather than formatted.
     *
     * <p>An earlier version concatenated this string by hand and leaned on a {@code $connect}
     * Lambda that rejected, with a 403, any token or event id containing a character it
     * would have had to escape. Both halves of that arrangement are gone: {@code $connect}
     * is now a mapping template with no place to run a regex, so the escaping has to be
     * done where the string is built or not at all.
     *
     * <p>It costs nothing to do properly. {@link JsonWriter} ships inside
     * {@code json-utils}, which the DynamoDB and API Gateway clients already pull in and
     * already load to parse their own responses — so this is a correct serialiser on a
     * classpath that has been paying for it since the first cold start. The INIT budget
     * that justified formatting by hand is not charged twice.
     */
    private static String admittedFrame(String eventId, String token, String admissionExpiresAt) {
        JsonWriter writer = JsonWriter.create();
        writer.writeStartObject();
        writer.writeFieldName("type").writeValue("ADMITTED");
        writer.writeFieldName("eventId").writeValue(eventId);
        writer.writeFieldName("token").writeValue(token);
        // Parsed, not passed through: a Number attribute that is not a number is a
        // producer bug, and failing the record here sends it to the DLQ with its
        // contents intact rather than emitting a frame with a hole where a long goes.
        writer.writeFieldName("admissionExpiresAt").writeValue(Long.parseLong(admissionExpiresAt));
        writer.writeEndObject();

        byte[] json = writer.getBytes();
        writer.close();
        return new String(json, StandardCharsets.UTF_8);
    }

    /**
     * Fails the record rather than pushing a frame with a hole in it. A message missing its
     * attributes is a producer bug, and the DLQ is where it should end up.
     */
    private static String attribute(SQSEvent.SQSMessage record, String name) {
        Map<String, SQSEvent.MessageAttribute> attributes = record.getMessageAttributes();
        SQSEvent.MessageAttribute attribute = attributes == null ? null : attributes.get(name);
        if (attribute == null || attribute.getStringValue() == null || attribute.getStringValue().isBlank()) {
            throw new IllegalArgumentException("Message attribute " + name + " is missing");
        }
        return attribute.getStringValue().trim();
    }

    /**
     * Holder idiom, not a plain static field. The client is built once per execution
     * environment and reused across invocations — so TLS setup and credential resolution
     * are paid on the cold start rather than every batch — but deferring it to first use
     * keeps the class loadable where the default region/credential chain resolves to
     * nothing, which is every test in this module.
     */
    private static final class SharedDynamo {
        private static final DynamoDbClient INSTANCE = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /**
     * Fails loudly at construction rather than posting to a {@code null} endpoint and
     * surfacing it later as an opaque SDK error.
     */
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable " + name + " is not set");
        }
        return value;
    }

    /**
     * The trailing newline is required, not cosmetic. {@code LambdaLogger.log} writes the
     * string verbatim with no line terminator, and a final unterminated write is discarded
     * when the invocation ends rather than flushed — so the summary line vanishes from
     * CloudWatch entirely while the invocation still reports success.
     *
     * <p>{@code context} is null in unit tests, where stdout is captured instead.
     */
    private static void log(Context context, String message) {
        if (context != null) {
            context.getLogger().log(message + System.lineSeparator());
        } else {
            System.out.println(message);
        }
    }
}
