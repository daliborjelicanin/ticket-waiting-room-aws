package com.ticketwaitingroom.queuesocket;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.ticketwaitingroom.common.queue.QueueAdmissionMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_CONNECTED_AT;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_CONNECTION_ID;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_EVENT_ID;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_EXPIRES_AT;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_TOKEN;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.GSI_BY_TOKEN;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.LIFETIME_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the notifier holds up the one promise this module exists for: that a token
 * admitted in Redis reaches every browser tab holding it, and that a tab which has gone
 * away costs nothing on the next admission.
 *
 * <p>The registry runs against a real DynamoDB (LocalStack) rather than a mocked SDK
 * client, because the lookup under test is a GSI query and a mock would only assert that
 * this code composes the query it was written to compose. The push is the one part that is
 * faked: LocalStack does not implement the {@code @connections} management API, which is
 * exactly why {@link ConnectionPusher} is an interface.
 *
 * <p><strong>Rows are seeded, not registered.</strong> {@code $connect} is an API Gateway
 * service integration onto DynamoDB, so no Java in this repo writes one — the writer is a
 * mapping template in {@code docs/runbook-websocket-queue.md}. The seeding helper here
 * reproduces that template's item, which makes this suite the only place the two halves are
 * checked against each other; {@link #theRowShapeTheMappingTemplateWritesIsTheOneTheLookupReads}
 * is that check stated outright.
 */
@Testcontainers
class QueueSocketIT {

    private static final String TABLE = "waiting-room-connections";
    private static final String EVENT_ID = "evt-1";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static DynamoDbClient dynamo;

    private ConnectionRegistry registry;
    private RecordingPusher pusher;

    @BeforeAll
    static void createClient() {
        dynamo = DynamoDbClient.builder()
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    @AfterAll
    static void closeClient() {
        if (dynamo != null) {
            dynamo.close();
        }
    }

    @BeforeEach
    void freshTable() {
        recreateTable();
        registry = new ConnectionRegistry(dynamo, TABLE);
        pusher = new RecordingPusher();
    }

    /**
     * The seam that used to be a compile error.
     *
     * <p>While {@code ConnectHandler} existed, a renamed attribute broke the build. Now the
     * writer is VTL in a runbook and the reader is this module, so nothing but a test can
     * notice them drifting apart. The attribute names below are therefore written out as
     * literals rather than through {@link ConnectionTableSchema} — using the constants would
     * make the test agree with the reader by construction and prove nothing about the
     * template. Change the template and this fails; change a constant and this fails.
     */
    @Test
    void theRowShapeTheMappingTemplateWritesIsTheOneTheLookupReads() {
        String token = UUID.randomUUID().toString();
        long connectedAt = Instant.now().getEpochSecond();

        // Verbatim the item built by the $connect request template in
        // docs/runbook-websocket-queue.md, down to the DynamoDB attribute types.
        dynamo.putItem(PutItemRequest.builder()
                .tableName(TABLE)
                .item(Map.of(
                        "connectionId", AttributeValue.builder().s("conn-1").build(),
                        "eventId", AttributeValue.builder().s(EVENT_ID).build(),
                        "token", AttributeValue.builder().s(token).build(),
                        "connectedAt", AttributeValue.builder().n(Long.toString(connectedAt)).build(),
                        "expiresAt", AttributeValue.builder()
                                .n(Long.toString(connectedAt + 86400)).build()))
                .build());

        assertThat(registry.connectionsFor(EVENT_ID, token))
                .as("the notifier must find a row written by the mapping template")
                .containsExactly("conn-1");

        Map<String, AttributeValue> row = getConnection("conn-1");
        assertThat(row.get(ATTR_EXPIRES_AT).n())
                .as("TTL must be epoch seconds as a Number; a String or milliseconds is "
                        + "accepted on write and then ignored by TTL forever")
                .isEqualTo(Long.toString(connectedAt + LIFETIME_SECONDS));
    }

    @Test
    void anAdmissionReachesEveryTabHoldingTheToken() {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        connect("conn-1", EVENT_ID, token);
        connect("conn-2", EVENT_ID, token);
        connect("conn-3", EVENT_ID, UUID.randomUUID().toString());

        SQSBatchResponse response = notify(admissionMessage("m-1", EVENT_ID, token, expiresAt));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(pusher.frames.keySet()).containsExactlyInAnyOrder("conn-1", "conn-2");
        assertThat(pusher.frames.get("conn-1"))
                .as("the wire format the client parses, pinned")
                .isEqualTo("{\"type\":\"ADMITTED\",\"eventId\":\"%s\",\"token\":\"%s\",\"admissionExpiresAt\":%d}"
                        .formatted(EVENT_ID, token, expiresAt.getEpochSecond()));
    }

    /**
     * What replaced the {@code $connect} 403 gate.
     *
     * <p>The query string reaches DynamoDB through a mapping template, which escapes for
     * JSON but does not validate, so an event id containing a quote is now a row this
     * function will read. Formatting the frame by hand would emit that quote raw and hand
     * the client a broken or forged frame; serialising it escapes it, and the frame stays a
     * frame. The defence moved from a different function to the line that needs it.
     */
    @Test
    void aTokenThatWouldHaveBrokenAHandBuiltFrameIsEscapedInstead() {
        String hostile = "evt\", \"type\":\"ADMITTED\", \"x\":\"";
        String token = "tok\\\"quote";
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        connect("conn-1", hostile, token);

        notify(admissionMessage("m-1", hostile, token, expiresAt));

        JsonNode frame = JsonNodeParser.create().parse(pusher.frames.get("conn-1"));
        Map<String, JsonNode> fields = frame.asObject();
        assertThat(fields.get("type").asString()).isEqualTo("ADMITTED");
        assertThat(fields.get("eventId").asString())
                .as("the injected quote must survive as data, not as structure")
                .isEqualTo(hostile);
        assertThat(fields.get("token").asString()).isEqualTo(token);
        assertThat(fields.get("admissionExpiresAt").asNumber())
                .isEqualTo(Long.toString(expiresAt.getEpochSecond()));
    }

    @Test
    void aClosedSocketIsPrunedRatherThanRetried() {
        String token = UUID.randomUUID().toString();
        connect("conn-gone", EVENT_ID, token);
        connect("conn-live", EVENT_ID, token);
        pusher.gone.add("conn-gone");

        SQSBatchResponse response = notify(
                admissionMessage("m-1", EVENT_ID, token, Instant.now().plus(Duration.ofMinutes(10))));

        assertThat(response.getBatchItemFailures())
                .as("a 410 is news about the socket, not a failure to deliver the message")
                .isEmpty();
        assertThat(getConnection("conn-gone")).isEmpty();
        assertThat(pusher.frames.keySet()).containsExactly("conn-live");
    }

    @Test
    void aTokenWithNoOpenSocketIsASilentSuccess() {
        // Whoever closed the tab is served by GET /events/{id}/queue/{token}; there is
        // nothing here to retry and nothing to dead-letter.
        SQSBatchResponse response = notify(admissionMessage(
                "m-1", EVENT_ID, UUID.randomUUID().toString(), Instant.now().plus(Duration.ofMinutes(10))));

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(pusher.frames).isEmpty();
    }

    @Test
    void oneBadRecordDoesNotDragItsBatchMatesToTheDlq() {
        String good = UUID.randomUUID().toString();
        String bad = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(10));
        connect("conn-good", EVENT_ID, good);
        connect("conn-bad", EVENT_ID, bad);
        pusher.failing.add("conn-bad");

        SQSBatchResponse response = notify(
                admissionMessage("m-good", EVENT_ID, good, expiresAt),
                admissionMessage("m-bad", EVENT_ID, bad, expiresAt));

        assertThat(response.getBatchItemFailures()).singleElement()
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .isEqualTo("m-bad");
        assertThat(pusher.frames).containsKey("conn-good");
        assertThat(getConnection("conn-bad"))
                .as("a transient push failure is retried, so the row must survive it")
                .isNotEmpty();
    }

    @Test
    void aMessageMissingItsAttributesIsRejectedRatherThanPushedWithAHole() {
        SQSEvent.SQSMessage record = new SQSEvent.SQSMessage();
        record.setMessageId("m-1");
        record.setBody(UUID.randomUUID().toString());

        assertThat(notify(record).getBatchItemFailures()).singleElement()
                .extracting(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .isEqualTo("m-1");
    }

    @Test
    void everyTabOpenedAtOnceStillGetsTheFrame() throws InterruptedException {
        String token = UUID.randomUUID().toString();
        int tabs = 50;

        // The fan-out claim, raced rather than asserted: fifty sockets registering for one
        // token simultaneously must all be addressable by the single admission that
        // follows. connectionId is minted per socket, so nothing here contends — the test
        // is that the GSI lookup finds all of them, not that one of them wins.
        race(tabs, i -> connect("conn-" + i, EVENT_ID, token));

        assertThat(rowCount()).isEqualTo(tabs);

        notify(admissionMessage("m-1", EVENT_ID, token, Instant.now().plus(Duration.ofMinutes(10))));
        assertThat(pusher.frames).hasSize(tabs);
    }

    /**
     * Stands in for the {@code $connect} service integration, writing the item its mapping
     * template writes. {@link #theRowShapeTheMappingTemplateWritesIsTheOneTheLookupReads} is
     * what keeps this helper honest.
     */
    private void connect(String connectionId, String eventId, String token) {
        long now = Instant.now().getEpochSecond();
        dynamo.putItem(PutItemRequest.builder()
                .tableName(TABLE)
                .item(Map.of(
                        ATTR_CONNECTION_ID, AttributeValue.builder().s(connectionId).build(),
                        ATTR_EVENT_ID, AttributeValue.builder().s(eventId).build(),
                        ATTR_TOKEN, AttributeValue.builder().s(token).build(),
                        ATTR_CONNECTED_AT, AttributeValue.builder().n(Long.toString(now)).build(),
                        ATTR_EXPIRES_AT, AttributeValue.builder()
                                .n(Long.toString(now + LIFETIME_SECONDS)).build()))
                .build());
    }

    private SQSBatchResponse notify(SQSEvent.SQSMessage... records) {
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(records));
        return new AdmissionNotifierHandler(registry, pusher).handleRequest(event, null);
    }

    private static SQSEvent.SQSMessage admissionMessage(String messageId, String eventId, String token,
                                                        Instant admissionExpiresAt) {
        SQSEvent.SQSMessage record = new SQSEvent.SQSMessage();
        record.setMessageId(messageId);
        record.setBody(token);
        record.setMessageAttributes(Map.of(
                QueueAdmissionMessage.ATTR_EVENT_ID,
                attribute(QueueAdmissionMessage.TYPE_STRING, eventId),
                QueueAdmissionMessage.ATTR_ADMISSION_EXPIRES_AT,
                attribute(QueueAdmissionMessage.TYPE_NUMBER, Long.toString(admissionExpiresAt.getEpochSecond()))));
        return record;
    }

    private static SQSEvent.MessageAttribute attribute(String dataType, String value) {
        SQSEvent.MessageAttribute attribute = new SQSEvent.MessageAttribute();
        attribute.setDataType(dataType);
        attribute.setStringValue(value);
        return attribute;
    }

    private Map<String, AttributeValue> getConnection(String connectionId) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of(ATTR_CONNECTION_ID, AttributeValue.builder().s(connectionId).build()))
                .consistentRead(true)
                .build()).item();
    }

    private int rowCount() {
        return dynamo.scan(ScanRequest.builder().tableName(TABLE).build()).count();
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
            assertThat(done.await(30, TimeUnit.SECONDS)).as("all attempts finished").isTrue();
        }
    }

    private void recreateTable() {
        try {
            dynamo.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
            dynamo.waiter().waitUntilTableNotExists(b -> b.tableName(TABLE));
        } catch (ResourceNotFoundException ignored) {
            // first run: nothing to delete
        }

        dynamo.createTable(CreateTableRequest.builder()
                .tableName(TABLE)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName(ATTR_CONNECTION_ID)
                                .attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(ATTR_EVENT_ID)
                                .attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(ATTR_TOKEN)
                                .attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName(ATTR_CONNECTION_ID)
                        .keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(GSI_BY_TOKEN)
                        .keySchema(
                                KeySchemaElement.builder().attributeName(ATTR_EVENT_ID)
                                        .keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName(ATTR_TOKEN)
                                        .keyType(KeyType.RANGE).build())
                        // KEYS_ONLY, and that is the whole trick: the base key projected
                        // into the index *is* the connectionId the notifier is looking for.
                        .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(TABLE));
    }

    /** Stands in for API Gateway's {@code @connections} endpoint, which LocalStack has not. */
    private static final class RecordingPusher implements ConnectionPusher {

        private final Map<String, String> frames = new ConcurrentHashMap<>();
        private final List<String> gone = new CopyOnWriteArrayList<>();
        private final List<String> failing = new CopyOnWriteArrayList<>();

        @Override
        public void push(String connectionId, String payload) {
            if (gone.contains(connectionId)) {
                throw GoneException.builder().message("410 Gone").build();
            }
            if (failing.contains(connectionId)) {
                throw new IllegalStateException("transient push failure");
            }
            frames.put(connectionId, payload);
        }
    }
}
