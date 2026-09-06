package com.ticketwaitingroom.waitingroom.queue;

import com.ticketwaitingroom.common.queue.QueueAdmissionMessage;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Publishes one message per admitted token to {@code queue-admissions}.
 *
 * <p>One message per token, not one per admit call. A per-call message would make the
 * notifier's unit of retry the whole batch, so a single unreachable socket would cause 499
 * other tokens to be re-notified on every attempt; per-token messages let SQS retry and
 * dead-letter exactly the one that failed.
 *
 * <p>The body is the bare token and the rest rides as message attributes — see
 * {@link QueueAdmissionMessage} for why the consumer must not have to parse JSON.
 */
@RequiredArgsConstructor
public class SqsAdmissionPublisher implements AdmissionPublisher {

    /** SQS's hard ceiling on a batch, not a tuning knob. */
    private static final int BATCH_LIMIT = 10;

    private final SqsClient sqs;
    private final String queueUrl;

    /**
     * Send every token, in parallel.
     *
     * <p>The parallelism is not premature. {@code max-admit-batch} is 500 and SQS caps a
     * batch at 10, so a full admission is 50 round trips — issued one after another that is
     * about a second added to the response of the one call this whole slice exists to keep
     * fast. Decoupling the push through a queue and then blocking the admit on the enqueue
     * would defeat the point.
     *
     * <p>Virtual threads rather than a pool: these are 50 blocking I/O calls that spend
     * their lives waiting on a socket, which is the case virtual threads exist for, and the
     * executor is scoped to the call so nothing outlives it. The try-with-resources close
     * waits for every send, so this method is still synchronous from the caller's side —
     * the latency collapses to roughly one round trip, it does not move somewhere invisible.
     */
    @Override
    public void publish(String eventId, List<String> tokens, Instant admissionExpiresAt) {
        String expiresAt = Long.toString(admissionExpiresAt.getEpochSecond());
        List<SendMessageBatchRequest> batches = batches(eventId, tokens, expiresAt);

        if (batches.size() == 1) {
            check(sqs.sendMessageBatch(batches.getFirst()));
            return;
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SendMessageBatchResponse>> sends = batches.stream()
                    .map(batch -> executor.submit(() -> sqs.sendMessageBatch(batch)))
                    .toList();
            for (Future<SendMessageBatchResponse> send : sends) {
                check(await(send));
            }
        }
    }

    private List<SendMessageBatchRequest> batches(String eventId, List<String> tokens, String expiresAt) {
        List<SendMessageBatchRequest> batches = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start += BATCH_LIMIT) {
            List<String> chunk = tokens.subList(start, Math.min(start + BATCH_LIMIT, tokens.size()));
            batches.add(SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(chunk.stream().map(token -> entry(token, eventId, expiresAt)).toList())
                    .build());
        }
        return batches;
    }

    /**
     * A batch send can fail <em>per entry</em> while the call itself returns 200, so the SDK
     * throws nothing and an ignored {@code failed()} list is a token silently never
     * announced. Raised as an exception because the caller's contract is already
     * "announcing is best-effort, and a failure is logged rather than propagated" — this
     * only makes sure there is something to log.
     */
    private static void check(SendMessageBatchResponse response) {
        if (response.hasFailed() && !response.failed().isEmpty()) {
            throw new IllegalStateException("SQS rejected " + response.failed().size()
                    + " of the admission messages, first: " + response.failed().getFirst().message());
        }
    }

    private static SendMessageBatchResponse await(Future<SendMessageBatchResponse> send) {
        try {
            return send.get();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("Could not publish an admission batch", failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing admissions", interrupted);
        }
    }

    private static SendMessageBatchRequestEntry entry(String token, String eventId, String expiresAt) {
        return SendMessageBatchRequestEntry.builder()
                // Unique within the batch only, which the token already is — a token
                // appears at most once in an admission because ZPOPMIN removed it.
                .id(token)
                .messageBody(token)
                .messageAttributes(Map.of(
                        QueueAdmissionMessage.ATTR_EVENT_ID, MessageAttributeValue.builder()
                                .dataType(QueueAdmissionMessage.TYPE_STRING).stringValue(eventId).build(),
                        QueueAdmissionMessage.ATTR_ADMISSION_EXPIRES_AT, MessageAttributeValue.builder()
                                .dataType(QueueAdmissionMessage.TYPE_NUMBER).stringValue(expiresAt).build()))
                .build();
    }
}
