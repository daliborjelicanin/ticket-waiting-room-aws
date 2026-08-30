package com.ticketwaitingroom.ticketissuance.messaging;

import tools.jackson.databind.json.JsonMapper;
import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import com.ticketwaitingroom.common.messaging.SqsMessagePump;
import com.ticketwaitingroom.ticketissuance.config.TicketIssuanceProperties;
import com.ticketwaitingroom.ticketissuance.ticket.IssueOutcome;
import com.ticketwaitingroom.ticketissuance.ticket.TicketIssuanceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Runs the issuance queue's {@link SqsMessagePump} for as long as the application is up.
 *
 * <p>{@code SmartLifecycle} rather than an {@code ApplicationRunner}: a runner starts the
 * loop and never learns the context is closing, so a task stop would kill consumers
 * mid-handler. This gets a {@link #stop()} callback, and it runs in the default (last)
 * phase, so polling starts only once the datasource and web layer are up and stops before
 * they go down.
 */
@Component
@RequiredArgsConstructor
public class TicketIssuanceConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuanceConsumer.class);

    private final SqsClient sqs;
    private final TicketIssuanceProperties properties;
    private final TicketIssuanceService issuanceService;
    // The autoconfigured mapper, injected as the concrete JsonMapper: Jackson 3 makes
    // ObjectMapper a supertype that XmlMapper also extends, so naming the JSON one is not
    // pedantry, it is what keeps this unambiguous if an XML data format ever lands.
    private final JsonMapper json;

    private volatile SqsMessagePump pump;

    @Override
    public boolean isAutoStartup() {
        return properties.consumer().enabled();
    }

    @Override
    public synchronized void start() {
        if (pump != null) {
            return;
        }
        var consumer = properties.consumer();
        pump = new SqsMessagePump(
                sqs,
                new SqsMessagePump.Settings(properties.issuanceQueueUrl(), "ticket-issuance",
                        consumer.threads(), consumer.batchSize(), consumer.pollWait()),
                this::handle);
        pump.start();
    }

    @Override
    public synchronized void stop() {
        if (pump != null) {
            pump.stop();
            pump = null;
        }
    }

    @Override
    public boolean isRunning() {
        return pump != null;
    }

    /**
     * Handle one delivery. Any exception thrown here means the message is not deleted, so
     * it is redelivered and eventually redriven to the DLQ — which is the correct outcome
     * for a body that will not parse, and for a database that is down.
     */
    private void handle(Message message) {
        TicketIssuanceMessage issuance = json.readValue(message.body(), TicketIssuanceMessage.class);
        IssueOutcome outcome = issuanceService.issue(issuance);

        switch (outcome) {
            case IssueOutcome.Issued issued -> log.info("issued ticket {} for order {} seat {}",
                    issued.ticket().getId(), issuance.orderId(), issuance.seatId());
            // Not a warning: at-least-once delivery makes this ordinary. It is logged at
            // all because a sustained run of them says something upstream is redelivering.
            case IssueOutcome.AlreadyIssued already -> log.info(
                    "duplicate delivery for order {} seat {} — ticket {} already issued",
                    issuance.orderId(), issuance.seatId(), already.ticket().getId());
        }
    }
}
