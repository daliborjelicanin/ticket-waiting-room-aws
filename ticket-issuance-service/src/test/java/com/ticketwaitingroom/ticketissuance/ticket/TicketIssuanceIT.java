package com.ticketwaitingroom.ticketissuance.ticket;

import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import com.ticketwaitingroom.common.tickets.TicketQrObjects;
import com.ticketwaitingroom.ticketissuance.qr.TicketQrStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the consumer half of the system: a purchase becomes exactly one ticket, however
 * many times SQS delivers the message that says so.
 *
 * <p>Runs against a real Postgres and a real SQS (Testcontainers + LocalStack) because
 * every guarantee under test belongs to one of them — a unique constraint, and at-least-once
 * delivery. Mocking either would leave the tests asserting that this code composes the
 * statements it was written to compose, which is not the question.
 *
 * <p>Each test uses a fresh order id, so they are independent without truncating between them.
 */
@SpringBootTest(properties = {
        "ticket-issuance.consumer.enabled=true",
        // The production 20s long poll would make every stop() take that long to unwind.
        "ticket-issuance.consumer.poll-wait=PT1S"
})
@Testcontainers
class TicketIssuanceIT {

    private static final String BUCKET = "ticket-qr-test";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static String issuanceQueueUrl;
    private static String qrQueueUrl;

    /**
     * Also where the queues and bucket are created. They have to exist before the
     * application context starts polling, and their URLs are only knowable once LocalStack
     * is up — which is exactly the window this callback occupies.
     */
    @DynamicPropertySource
    static void awsAndDatasource(DynamicPropertyRegistry registry) {
        try (SqsClient sqs = sqsClient(); S3Client s3 = s3Client()) {
            issuanceQueueUrl = sqs.createQueue(q -> q.queueName("ticket-issuance-queue")).queueUrl();
            qrQueueUrl = sqs.createQueue(q -> q.queueName("qr-generation-queue")).queueUrl();
            s3.createBucket(b -> b.bucket(BUCKET));
        }

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("aws.region", LOCALSTACK::getRegion);
        registry.add("aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("ticket-issuance.issuance-queue-url", () -> issuanceQueueUrl);
        registry.add("ticket-issuance.qr-queue-url", () -> qrQueueUrl);
        registry.add("ticket-issuance.qr-bucket", () -> BUCKET);
    }

    @Autowired
    private TicketIssuanceService issuanceService;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TicketQrStore qrStore;
    @Autowired
    private SqsClient sqs;
    @Autowired
    private S3Client s3;
    @Autowired
    private JsonMapper json;

    @Test
    void concurrentDeliveriesIssueExactlyOneTicket() throws InterruptedException {
        TicketIssuanceMessage message = purchase();
        int deliveries = 20;
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        var ticketIds = new ConcurrentLinkedQueue<UUID>();

        // Twenty consumers processing twenty copies of the same message at the same instant.
        // Nothing in the application serialises them: the unique constraint is the only
        // thing standing between this and twenty tickets for one seat.
        race(deliveries, i -> {
            IssueOutcome outcome = issuanceService.issue(message);
            ticketIds.add(outcome.ticket().getId());
            switch (outcome) {
                case IssueOutcome.Issued ignored -> issued.incrementAndGet();
                case IssueOutcome.AlreadyIssued ignored -> duplicates.incrementAndGet();
            }
        });

        assertThat(issued).as("exactly one delivery creates the ticket").hasValue(1);
        assertThat(duplicates).hasValue(deliveries - 1);
        assertThat(Set.copyOf(ticketIds))
                .as("every delivery, winner and losers alike, resolves to the same ticket")
                .hasSize(1);
        assertThat(ticketRepository.findByOrderIdOrderBySeatId(orderIdOf(message))).hasSize(1);
    }

    @Test
    void aDuplicateDeliveryStillRequestsTheQr() {
        TicketIssuanceMessage message = purchase();
        drain(qrQueueUrl);

        UUID first = issuanceService.issue(message).ticket().getId();
        UUID second = issuanceService.issue(message).ticket().getId();

        // The insert and the QR request are not atomic, so a crash between them leaves a
        // ticket with no image. The redelivery is the repair — which only works if the
        // duplicate path re-requests rather than short-circuiting as a no-op.
        assertThat(second).isEqualTo(first);
        assertThat(bodiesOn(qrQueueUrl, 2))
                .as("both deliveries queue a render for the same ticket")
                .containsExactly(first.toString(), first.toString());
    }

    @Test
    void theConsumerDrainsTheQueueAndIssuesOnce() {
        TicketIssuanceMessage message = purchase();
        UUID orderId = orderIdOf(message);
        String body = json.writeValueAsString(message);

        // Four copies of one purchase, as a retrying saga plus SQS redelivery would produce.
        for (int i = 0; i < 4; i++) {
            sqs.sendMessage(send -> send.queueUrl(issuanceQueueUrl).messageBody(body));
        }

        await("all four deliveries are consumed and acknowledged",
                () -> ticketRepository.findByOrderIdOrderBySeatId(orderId).size() == 1
                        && messagesOnQueue(issuanceQueueUrl) == 0);

        assertThat(ticketRepository.findByOrderIdOrderBySeatId(orderId))
                .as("four deliveries, one ticket")
                .hasSize(1);
    }

    @Test
    void qrDownloadIsPendingUntilTheObjectExists() {
        Ticket ticket = issuanceService.issue(purchase()).ticket();

        assertThat(qrStore.presign(ticket.getQrObjectKey()))
                .as("the ticket is valid but its image is still behind a queue")
                .isEmpty();

        s3.putObject(put -> put.bucket(BUCKET).key(TicketQrObjects.key(ticket.getId()))
                        .contentType(TicketQrObjects.CONTENT_TYPE),
                RequestBody.fromBytes(new byte[]{1, 2, 3}));

        assertThat(qrStore.presign(ticket.getQrObjectKey()))
                .get()
                .satisfies(presigned -> {
                    assertThat(presigned.url()).contains(TicketQrObjects.key(ticket.getId()));
                    assertThat(presigned.url())
                            .as("a presigned GET, not a bare object URL — the bucket is private")
                            .contains("X-Amz-Signature");
                    assertThat(presigned.expiresAt()).isAfter(Instant.now());
                });
    }

    private static TicketIssuanceMessage purchase() {
        return new TicketIssuanceMessage(UUID.randomUUID().toString(), "evt-issue", "A-1",
                "cust-1", "gw-ref-1", 12000L);
    }

    private static UUID orderIdOf(TicketIssuanceMessage message) {
        return UUID.fromString(message.orderId());
    }

    private void drain(String queueUrl) {
        sqs.purgeQueue(purge -> purge.queueUrl(queueUrl));
        await("queue drained", () -> messagesOnQueue(queueUrl) == 0);
    }

    private List<String> bodiesOn(String queueUrl, int expected) {
        await("%d messages on the queue".formatted(expected), () -> messagesOnQueue(queueUrl) >= expected);
        return sqs.receiveMessage(receive -> receive.queueUrl(queueUrl).maxNumberOfMessages(expected))
                .messages().stream().map(Message::body).collect(Collectors.toList());
    }

    /**
     * Visible <em>and</em> in-flight. Counting only visible messages makes every one of
     * these assertions a coin flip, because a message being processed right now is
     * invisible and would read as an empty queue.
     */
    private int messagesOnQueue(String queueUrl) {
        var attributes = sqs.getQueueAttributes(request -> request
                .queueUrl(queueUrl)
                .attributeNamesWithStrings("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible"))
                .attributesAsStrings();
        return Integer.parseInt(attributes.getOrDefault("ApproximateNumberOfMessages", "0"))
                + Integer.parseInt(attributes.getOrDefault("ApproximateNumberOfMessagesNotVisible", "0"));
    }

    /** Polls until the condition holds, so tests never depend on a fixed sleep. */
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

    /** Virtual threads released by a start gate at once, for maximum contention. */
    private void race(int attempts, IntConsumer attempt) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                int index = i;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        attempt.accept(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all deliveries finished").isTrue();
        }
    }

    private static SqsClient sqsClient() {
        return SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(localCredentials())
                .build();
    }

    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .forcePathStyle(true)
                .credentialsProvider(localCredentials())
                .build();
    }

    private static StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
    }
}
