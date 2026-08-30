package com.ticketwaitingroom.notification.messaging;

import com.ticketwaitingroom.common.events.PurchaseConfirmedEvent;
import com.ticketwaitingroom.notification.notify.Email;
import com.ticketwaitingroom.notification.notify.NotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the notification consumer against a real SQS: a raw-delivered SNS payload becomes
 * one email, and a send that fails is retried rather than lost.
 *
 * <p>The queue is created with a 1-second visibility timeout so a redelivery is observable
 * inside a test rather than 30 seconds later.
 */
@SpringBootTest(properties = {
        "notification.consumer.enabled=true",
        "notification.consumer.poll-wait=PT1S",
        "notification.from-address=no-reply@example.test",
        "notification.fallback-recipient=verified@example.test",
        "notification.ticket-base-url=https://tickets.example.test"
})
@Testcontainers
class PurchaseConfirmedConsumerIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static String queueUrl;

    @DynamicPropertySource
    static void aws(DynamicPropertyRegistry registry) {
        try (SqsClient sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build()) {
            queueUrl = sqs.createQueue(queue -> queue
                    .queueName("purchase-confirmed-notifications")
                    .attributesWithStrings(Map.of("VisibilityTimeout", "1"))).queueUrl();
        }

        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("aws.ses.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("notification.queue-url", () -> queueUrl);
    }

    /**
     * {@code @Primary} rather than a bean-definition override: the real
     * {@code NotificationSender} bean still exists and is still constructed, so this test
     * cannot pass against a configuration that fails to define one.
     */
    @TestConfiguration
    static class RecordingSenderConfig {

        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    static class RecordingSender implements NotificationSender {

        final List<Email> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        volatile int failuresRemaining;

        @Override
        public void send(Email email) {
            attempts.incrementAndGet();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IllegalStateException("SES is having a day");
            }
            sent.add(email);
        }
    }

    @Autowired
    private RecordingSender sender;
    @Autowired
    private SqsClient sqs;
    @Autowired
    private JsonMapper json;

    @BeforeEach
    void resetSender() {
        sender.sent.clear();
        sender.attempts.set(0);
        sender.failuresRemaining = 0;
    }

    @Test
    void aRawDeliveredPayloadBecomesOneEmail() {
        PurchaseConfirmedEvent event = purchase();

        publish(event);

        await("the confirmation is sent", () -> !sender.sent.isEmpty());
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.getFirst().recipient()).isEqualTo("verified@example.test");
        assertThat(sender.sent.getFirst().body()).contains(event.seatId()).contains(event.orderId());
        await("the message is acknowledged", () -> messagesOnQueue() == 0);
    }

    @Test
    void aFailedSendIsRedeliveredRatherThanLost() {
        sender.failuresRemaining = 2;

        publish(purchase());

        // The pump never deletes a message its handler threw on, so SQS redelivers it once
        // the visibility timeout lapses. Without that, an SES blip would silently swallow a
        // customer's confirmation with nothing on any queue to show for it.
        await("the send eventually succeeds after retries", () -> !sender.sent.isEmpty());
        assertThat(sender.attempts.get()).isGreaterThan(1);
        await("the message is acknowledged once it succeeds", () -> messagesOnQueue() == 0);
    }

    @Test
    void anUnparseablePayloadIsNeverAcknowledged() throws InterruptedException {
        sqs.sendMessage(send -> send.queueUrl(queueUrl).messageBody("{ this is not the contract }"));

        // A fixed observation window rather than an await, because the claim is an absence:
        // several visibility timeouts pass (they are 1s here) and the message is still not
        // deleted. It keeps coming back until the redrive policy parks it in the DLQ, where
        // a human can see it — deleting it instead would be a purchase nobody was ever told
        // about, with nothing left anywhere to say so.
        Thread.sleep(Duration.ofSeconds(4));

        assertThat(sender.attempts.get()).as("the mailer is never reached").isZero();
        assertThat(sender.sent).isEmpty();
        assertThat(messagesOnQueue()).as("still on the queue after several redeliveries").isGreaterThanOrEqualTo(1);

        sqs.purgeQueue(purge -> purge.queueUrl(queueUrl));
    }

    private void publish(PurchaseConfirmedEvent event) {
        // Raw message delivery means the SQS body is exactly what was published to SNS.
        sqs.sendMessage(send -> send.queueUrl(queueUrl).messageBody(json.writeValueAsString(event)));
    }

    private static PurchaseConfirmedEvent purchase() {
        return new PurchaseConfirmedEvent(UUID.randomUUID().toString(), "evt-notify", "A-7",
                "cust-1", "gw-ref-1", 12000L);
    }

    /**
     * Visible <em>and</em> in-flight. Counting only visible messages makes every one of
     * these assertions a coin flip, because a message being processed right now is
     * invisible and would read as an empty queue.
     */
    private int messagesOnQueue() {
        var attributes = sqs.getQueueAttributes(request -> request
                .queueUrl(queueUrl)
                .attributeNamesWithStrings("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"))
                .attributesAsStrings();
        return Integer.parseInt(attributes.getOrDefault("ApproximateNumberOfMessages", "0"))
                + Integer.parseInt(attributes.getOrDefault("ApproximateNumberOfMessagesNotVisible", "0"));
    }

    private static void await(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(Duration.ofMillis(200));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out after " + TIMEOUT + " waiting for: " + what);
    }
}
