package com.ticketwaitingroom.qrgeneration;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ticketwaitingroom.common.tickets.TicketQrObjects;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renders ticket QR codes onto S3, driven by an SQS event source mapping on
 * {@code qr-generation-queue}.
 *
 * <p><strong>The message body is a bare ticket id, not JSON.</strong> Everything else is
 * derived from it — the object key and the encoded verification URL both, via
 * {@link TicketQrObjects} — which means this function needs no JSON parser. That is not
 * minimalism for its own sake: slice 2 measured Lambda's separate 10-second INIT budget
 * being blown by class loading alone, and every dependency not on this classpath is
 * cold-start time not spent. The cost is that the message carries no context for a human
 * reading the DLQ, which is why the ticket id is echoed in every log line here.
 *
 * <p>Idempotency needs no mechanism. The key is deterministic and {@link QrRenderer} is
 * pure, so a redelivered message rewrites the identical object.
 */
public class QrGenerationHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final String BUCKET_ENV = "QR_BUCKET";
    private static final String VERIFY_BASE_URL_ENV = "TICKET_VERIFY_BASE_URL";

    private final QrObjectStore store;
    private final QrRenderer renderer;
    private final String verifyBaseUrl;

    /** Lambda's entry point: constructed once per execution environment. */
    public QrGenerationHandler() {
        this(new QrObjectStore(SharedClient.INSTANCE, requiredEnv(BUCKET_ENV)),
                new QrRenderer(),
                requiredEnv(VERIFY_BASE_URL_ENV));
    }

    QrGenerationHandler(QrObjectStore store, QrRenderer renderer, String verifyBaseUrl) {
        this.store = store;
        this.renderer = renderer;
        this.verifyBaseUrl = verifyBaseUrl;
    }

    /**
     * Render every record in the batch, reporting per-record failures rather than throwing.
     *
     * <p>Throwing would fail the whole batch, and SQS would then redeliver all ten
     * messages — nine of which succeeded — so a single unparseable id would drag its
     * batch-mates towards the DLQ with it and re-render nine images every attempt. The
     * partial response avoids that, but <strong>only if the event source mapping has
     * {@code ReportBatchItemFailures} enabled</strong>: without it Lambda ignores this
     * return value entirely and treats the invocation as a blanket success, which silently
     * drops the failures instead.
     */
    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage record : event.getRecords()) {
            String ticketId = record.getBody() == null ? "" : record.getBody().trim();
            try {
                generate(ticketId);
                log(context, "rendered QR for ticket=%s key=%s".formatted(ticketId, TicketQrObjects.key(ticketId)));
            } catch (RuntimeException failed) {
                // Reported, not rethrown: this record alone goes back to the queue.
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(record.getMessageId())
                        .build());
                log(context, "failed QR for ticket=%s messageId=%s — %s: %s".formatted(
                        ticketId, record.getMessageId(), failed.getClass().getSimpleName(), failed.getMessage()));
            }
        }

        log(context, "qr generation complete records=%d failures=%d"
                .formatted(event.getRecords().size(), failures.size()));
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    private void generate(String ticketId) {
        // Parsed only to reject a body that is not a ticket id at all. A malformed record
        // must fail here, before it can write an object under an attacker-chosen key.
        UUID parsed = UUID.fromString(ticketId);
        store.store(parsed.toString(), renderer.renderPng(
                TicketQrObjects.verificationUrl(verifyBaseUrl, parsed.toString())));
    }

    /**
     * Holder idiom, not a plain static field. The client is built once per execution
     * environment and reused across invocations — so TLS setup and credential resolution
     * are paid on the cold start rather than every batch — but deferring it to first use
     * keeps the class loadable where the default region/credential chain resolves to
     * nothing, which is every test in this module.
     */
    private static final class SharedClient {
        private static final S3Client INSTANCE = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /**
     * Fails loudly at construction rather than writing to a {@code null} bucket and
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
