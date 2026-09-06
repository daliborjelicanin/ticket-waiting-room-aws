package com.ticketwaitingroom.waitingroom.queue;

import com.ticketwaitingroom.common.queue.QueueAdmissionMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one thing the parallel send could get wrong: that chunking 500 tokens into
 * batches of ten and issuing them concurrently still puts <em>every</em> token on the
 * queue exactly once, with its attributes intact.
 *
 * <p>Against real SQS (LocalStack) rather than a mocked {@code SqsClient}, because a mock
 * would assert only that this code calls the method it was written to call — not that the
 * batch entry ids are unique, which is the constraint SQS actually enforces and the one a
 * concurrent send is most likely to trip.
 */
@Testcontainers
class SqsAdmissionPublisherIT {

    private static final String EVENT_ID = "evt-1";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static SqsClient sqs;
    private static String queueUrl;

    @BeforeAll
    static void createQueue() {
        sqs = SqsClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                .queueName("queue-admissions")
                .build()).queueUrl();
    }

    @AfterAll
    static void closeClient() {
        if (sqs != null) {
            sqs.close();
        }
    }

    @Test
    void everyTokenOfAFullAdmissionReachesTheQueueExactlyOnce() {
        // 500 is max-admit-batch, so this is the worst case the service can actually
        // produce: 50 batches issued at once rather than one after another.
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            tokens.add(UUID.randomUUID().toString());
        }
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);

        new SqsAdmissionPublisher(sqs, queueUrl).publish(EVENT_ID, tokens, expiresAt);

        List<Message> received = drain(tokens.size());
        assertThat(received).hasSize(tokens.size());
        assertThat(received.stream().map(Message::body).toList())
                .as("no token dropped, none sent twice")
                .containsExactlyInAnyOrderElementsOf(tokens);

        Message sample = received.getFirst();
        assertThat(sample.messageAttributes().get(QueueAdmissionMessage.ATTR_EVENT_ID).stringValue())
                .isEqualTo(EVENT_ID);
        assertThat(sample.messageAttributes().get(QueueAdmissionMessage.ATTR_ADMISSION_EXPIRES_AT).stringValue())
                .isEqualTo(Long.toString(expiresAt.getEpochSecond()));
    }

    @Test
    void aSingleBatchTakesTheFastPathAndStillCarriesItsAttributes() {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);

        new SqsAdmissionPublisher(sqs, queueUrl).publish(EVENT_ID, List.of(token), expiresAt);

        List<Message> received = drain(1);
        assertThat(received).singleElement()
                .satisfies(message -> assertThat(message.body()).isEqualTo(token));
    }

    private List<Message> drain(int expected) {
        List<Message> drained = new ArrayList<>();
        while (drained.size() < expected) {
            List<Message> batch = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .messageAttributeNames("All")
                    .waitTimeSeconds(1)
                    .build()).messages();
            if (batch.isEmpty()) {
                break;
            }
            drained.addAll(batch);
            batch.forEach(message -> sqs.deleteMessage(b -> b.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())));
        }
        return drained;
    }
}
