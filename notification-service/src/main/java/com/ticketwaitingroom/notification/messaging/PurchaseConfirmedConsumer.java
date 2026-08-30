package com.ticketwaitingroom.notification.messaging;

import tools.jackson.databind.json.JsonMapper;
import com.ticketwaitingroom.common.events.PurchaseConfirmedEvent;
import com.ticketwaitingroom.common.messaging.SqsMessagePump;
import com.ticketwaitingroom.notification.config.NotificationProperties;
import com.ticketwaitingroom.notification.notify.PurchaseConfirmationMailer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Consumes the SQS queue subscribed to the {@code purchase-confirmed} SNS topic.
 *
 * <p>The topic's second subscriber is a plain email subscription, and having both is the
 * point: one event, two independent deliveries, neither aware of the other. That is what
 * SNS is here for — an SQS queue alone would have forced this service to also become the
 * thing that emails a human directly.
 *
 * <p><strong>The subscription must have raw message delivery enabled.</strong> Without it
 * SNS wraps every payload in its own envelope, and the body deserialises into a
 * {@link PurchaseConfirmedEvent} of all nulls rather than failing — which shows up as an
 * email addressed to nobody about seat null, not as a parse error.
 *
 * <p><strong>A redelivery sends a second email.</strong> SQS is at-least-once and SES has
 * no idempotency key, so this is the one place in the project where at-least-once is
 * visible to a person. It is not papered over with an in-memory guard, which would be a
 * dedupe that lasts exactly as long as one task and lies about the rest. The honest fix is
 * a {@code notifications_sent} table with a unique key on {@code orderId}, written before
 * the send, deliberately deferred: it means giving this service a database, and a duplicate
 * confirmation email is a far cheaper defect than a duplicate ticket or a duplicate charge.
 */
@Component
@RequiredArgsConstructor
public class PurchaseConfirmedConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PurchaseConfirmedConsumer.class);

    private final SqsClient sqs;
    private final NotificationProperties properties;
    private final PurchaseConfirmationMailer mailer;
    // The autoconfigured mapper, injected as the concrete JsonMapper: Jackson 3 makes
    // ObjectMapper a supertype that XmlMapper also extends, so naming the JSON one is not
    // pedantry, it is what keeps this unambiguous if an XML dataformat ever lands.
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
                new SqsMessagePump.Settings(properties.queueUrl(), "purchase-confirmed",
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
     * Any exception thrown here means the message is not deleted, so it is redelivered and
     * eventually redriven to the DLQ. That is the right outcome for an SES outage; it is
     * also the right outcome for a body that will not parse, which is otherwise a
     * notification silently dropped with nothing to show for it.
     */
    private void handle(Message message) throws Exception {
        PurchaseConfirmedEvent event = json.readValue(message.body(), PurchaseConfirmedEvent.class);
        mailer.send(event);
        log.info("sent purchase confirmation for order {} seat {}", event.orderId(), event.seatId());
    }
}
