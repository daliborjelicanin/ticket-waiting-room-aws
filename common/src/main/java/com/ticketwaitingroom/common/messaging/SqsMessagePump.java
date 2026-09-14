package com.ticketwaitingroom.common.messaging;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A long-polling SQS consumer loop, written out rather than delegated to a framework
 * annotation because the delivery semantics it implements are the point of this slice.
 *
 * <p>Three properties, in the order they matter:
 *
 * <ol>
 *   <li><strong>Delete only after the handler returns.</strong> Deleting on receipt would
 *       make the queue an at-most-once channel and a crash mid-handler would lose the work
 *       outright. This is the whole reason the loop is handwritten and visible.
 *   <li><strong>A failed handler leaves the message alone.</strong> No delete, no explicit
 *       nack — the visibility timeout expires and SQS redelivers, and after
 *       {@code maxReceiveCount} the redrive policy moves it to the dead-letter queue.
 *       Nothing here needs to know that number; the queue owns the retry budget.
 *   <li><strong>Redelivery is expected, not exceptional.</strong> SQS Standard is
 *       at-least-once by design, and this pump adds two more sources of duplication: a
 *       handler that succeeds but whose delete call fails, and a shutdown mid-batch that
 *       abandons already-received messages to their visibility timeout. Every handler
 *       wired to this class must therefore be idempotent. That is a requirement of the
 *       transport, not a defect of this loop.
 * </ol>
 *
 * <p>Consumers are virtual threads: the work is a blocking receive followed by blocking
 * I/O, which is exactly the case virtual threads exist for, so concurrency is raised by
 * adding consumers rather than by making any of this asynchronous.
 *
 * <p>Not a Spring bean and deliberately Spring-free — {@code common} carries no framework.
 * Each service wraps an instance in whatever lifecycle it uses.
 */
public final class SqsMessagePump {

    private static final Logger LOG = System.getLogger(SqsMessagePump.class.getName());

    /** How long a consumer waits before retrying after the receive call itself fails. */
    private static final Duration RECEIVE_ERROR_BACKOFF = Duration.ofSeconds(2);

    private final SqsClient sqs;
    private final Settings settings;
    private final MessageHandler handler;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ExecutorService consumers;

    public SqsMessagePump(SqsClient sqs, Settings settings, MessageHandler handler) {
        this.sqs = sqs;
        this.settings = settings;
        this.handler = handler;
    }

    /**
     * What the pump polls and how hard.
     *
     * @param queueUrl  the queue to consume
     * @param name      used in log lines, so two pumps in one JVM are distinguishable
     * @param consumers concurrent receive loops
     * @param batchSize messages per receive, 1-10 (SQS's own ceiling)
     * @param pollWait  long-poll duration, 0-20s; anything above zero is what makes this a
     *                  long poll rather than a busy loop billing a request per empty check
     */
    public record Settings(
            String queueUrl,
            String name,
            int consumers,
            int batchSize,
            Duration pollWait
    ) {
        public Settings {
            if (queueUrl == null || queueUrl.isBlank()) {
                throw new IllegalArgumentException("queueUrl is required");
            }
            if (consumers < 1) {
                throw new IllegalArgumentException("consumers must be at least 1, was " + consumers);
            }
            if (batchSize < 1 || batchSize > 10) {
                throw new IllegalArgumentException("batchSize must be 1..10, was " + batchSize);
            }
            if (pollWait.isNegative() || pollWait.toSeconds() > 20) {
                throw new IllegalArgumentException("pollWait must be 0..20s, was " + pollWait);
            }
        }
    }

    /** Allowed to throw: throwing is how a handler says "do not delete this message". */
    @FunctionalInterface
    public interface MessageHandler {
        void handle(Message message) throws Exception;
    }

    /** Idempotent — a second call while running does nothing. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consumers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < settings.consumers(); i++) {
            consumers.submit(this::pollLoop);
        }
        LOG.log(Level.INFO, () -> "%s: %d consumers polling %s"
                .formatted(settings.name(), settings.consumers(), settings.queueUrl()));
    }

    /**
     * Stops polling and interrupts consumers blocked in a receive.
     *
     * <p>Messages already received but not yet handled are simply abandoned — they become
     * visible again when their visibility timeout lapses. Draining them instead would mean
     * blocking shutdown on a handler that may be waiting on a database, which turns a task
     * stop into a stuck task.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ExecutorService executor = consumers;
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            // The poll itself blocks for up to pollWait; give it that plus room for the
            // in-flight HTTP call to unwind before giving up on a clean stop.
            if (!executor.awaitTermination(settings.pollWait().toSeconds() + 5, TimeUnit.SECONDS)) {
                LOG.log(Level.WARNING, () -> settings.name() + ": consumers did not stop in time");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        LOG.log(Level.INFO, () -> settings.name() + ": stopped");
    }

    private void pollLoop() {
        while (running.get()) {
            List<Message> batch;
            try {
                batch = receive();
            } catch (SdkException receiveFailed) {
                // Almost always transient (throttling, a dropped connection, credentials
                // being refreshed). Backing off beats hammering the API in a tight loop.
                LOG.log(Level.WARNING, settings.name() + ": receive failed", receiveFailed);
                if (!sleep(RECEIVE_ERROR_BACKOFF)) {
                    return;
                }
                continue;
            }

            for (Message message : batch) {
                if (!running.get()) {
                    return;
                }
                process(message);
            }
        }
    }

    private List<Message> receive() {
        return sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(settings.queueUrl())
                        .maxNumberOfMessages(settings.batchSize())
                        .waitTimeSeconds((int) settings.pollWait().toSeconds())
                        // The receive count is carried purely for the log line below: above 1
                        // it is the visible symptom of a handler that keeps failing, and the
                        // only warning before the message lands in the DLQ. The trace header
                        // is what the producer's SDK wrote so the consumer can continue its
                        // trace; this loop never reads it, but it must ask for it or the
                        // attribute is simply not delivered and every consumer starts a trace
                        // of its own.
                        .messageSystemAttributeNames(
                                MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                                MessageSystemAttributeName.AWS_TRACE_HEADER)
                        .build())
                .messages();
    }

    private void process(Message message) {
        String receiveCount = message.attributesAsStrings()
                .getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT.toString(), "1");
        if (!"1".equals(receiveCount)) {
            LOG.log(Level.INFO, () -> "%s: redelivery #%s of message %s"
                    .formatted(settings.name(), receiveCount, message.messageId()));
        }

        try {
            handler.handle(message);
        } catch (Exception failed) {
            LOG.log(Level.WARNING, "%s: handler failed for message %s — left on the queue for redrive"
                    .formatted(settings.name(), message.messageId()), failed);
            return;
        }

        try {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(settings.queueUrl())
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (SdkException deleteFailed) {
            // The work is done and committed; only the acknowledgement was lost. The
            // message will be redelivered and the handler's idempotency will absorb it.
            LOG.log(Level.WARNING, "%s: handled message %s but could not delete it — expect a redelivery"
                    .formatted(settings.name(), message.messageId()), deleteFailed);
        }
    }

    /** @return false if the wait was interrupted, meaning the caller should stop */
    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
