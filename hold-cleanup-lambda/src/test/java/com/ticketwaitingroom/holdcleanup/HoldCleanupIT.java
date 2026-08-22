package com.ticketwaitingroom.holdcleanup;

import com.ticketwaitingroom.common.events.SeatHoldExpiredEvent;
import com.ticketwaitingroom.common.inventory.SeatTableSchema;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_EVENT_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_HOLD_EXPIRES_AT;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_HOLD_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_PK;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_SEAT_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_SK;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_STATUS;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.STATUS_AVAILABLE;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.STATUS_HELD;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.STATUS_SOLD;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.pk;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.sk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cleanup Lambda reclaims exactly the seats it should, and — the part that
 * matters — that reclaiming is safe against a purchase landing at the same instant.
 *
 * <p>Runs against a real DynamoDB (LocalStack) rather than a mocked SDK client, because
 * the guarantee under test <em>is</em> DynamoDB's conditional-write serialisation. A mock
 * would assert only that this code composes the condition it was written to compose.
 */
@Testcontainers
class HoldCleanupIT {

    private static final String TABLE = "seat-inventory";
    private static final String EVENT_ID = "evt-1";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static DynamoDbClient dynamo;
    private ExpiredHoldRepository repository;

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
        repository = new ExpiredHoldRepository(dynamo, TABLE);
    }

    @Test
    void reclaimsAnExpiredHoldBackToAvailable() {
        Instant now = Instant.now();
        putSeat("A-1", STATUS_HELD, "hold-1", now.minus(Duration.ofSeconds(30)));

        HoldCleanupHandler.Summary summary = runCleanupAt(now);

        assertThat(summary.expiredFound()).isEqualTo(1);
        assertThat(summary.reclaimed()).isEqualTo(1);
        assertThat(summary.lostRace()).isZero();

        Map<String, AttributeValue> seat = getSeat("A-1");
        assertThat(seat.get(ATTR_STATUS).s()).isEqualTo(STATUS_AVAILABLE);
        assertThat(seat)
                .as("a reclaimed seat must carry no trace of the dead hold — a lingering "
                        + "holdExpiresAt would leave the seat scheduled for TTL deletion")
                .doesNotContainKeys(ATTR_HOLD_ID, ATTR_HOLD_EXPIRES_AT);
    }

    @Test
    void reclaimedSeatCanBeHeldAgain() {
        Instant now = Instant.now();
        putSeat("A-1", STATUS_HELD, "hold-1", now.minus(Duration.ofSeconds(30)));

        runCleanupAt(now);

        // The point of the whole slice: inventory is genuinely back, not just relabelled.
        assertThat(tryHold("A-1", "hold-2", now.plus(Duration.ofMinutes(5)))).isTrue();
        assertThat(getSeat("A-1").get(ATTR_HOLD_ID).s()).isEqualTo("hold-2");
    }

    @Test
    void leavesLiveHoldsSoldAndAvailableSeatsAlone() {
        Instant now = Instant.now();
        putSeat("A-1", STATUS_HELD, "hold-live", now.plus(Duration.ofMinutes(5)));
        putSeat("A-2", STATUS_SOLD, "hold-sold", null);
        putSeat("A-3", STATUS_AVAILABLE, null, null);

        HoldCleanupHandler.Summary summary = runCleanupAt(now);

        assertThat(summary.expiredFound()).isZero();
        assertThat(getSeat("A-1").get(ATTR_STATUS).s()).isEqualTo(STATUS_HELD);
        assertThat(getSeat("A-2").get(ATTR_STATUS).s()).isEqualTo(STATUS_SOLD);
        assertThat(getSeat("A-3").get(ATTR_STATUS).s()).isEqualTo(STATUS_AVAILABLE);
    }

    @Test
    void neverReclaimsASoldSeatThatKeptAStaleTtl() {
        // Defence in depth: confirmPurchase removes holdExpiresAt, but if a future code
        // path ever forgets to, the status guard must still refuse to un-sell the seat.
        Instant now = Instant.now();
        putSeat("A-1", STATUS_SOLD, "hold-1", now.minus(Duration.ofSeconds(30)));

        assertThat(runCleanupAt(now).reclaimed()).isZero();
        assertThat(getSeat("A-1").get(ATTR_STATUS).s()).isEqualTo(STATUS_SOLD);
    }

    @Test
    void staleScanResultCannotTearUpARefreshedHold() {
        Instant now = Instant.now();
        putSeat("A-1", STATUS_HELD, "hold-old", now.minus(Duration.ofSeconds(30)));

        // Simulates the gap between scan and write: the scan saw hold-old, but by the time
        // the reclaim is issued the seat has expired, been reclaimed, and been re-held.
        SeatHoldExpiredEvent staleScanResult = repository.findExpiredHolds(now).getFirst();
        putSeat("A-1", STATUS_HELD, "hold-new", now.plus(Duration.ofMinutes(5)));

        assertThat(repository.reclaim(staleScanResult, now)).isFalse();

        Map<String, AttributeValue> seat = getSeat("A-1");
        assertThat(seat.get(ATTR_STATUS).s()).isEqualTo(STATUS_HELD);
        assertThat(seat.get(ATTR_HOLD_ID).s())
                .as("the new owner's hold survives a stale reclaim")
                .isEqualTo("hold-new");
    }

    @Test
    void cleanupAndPurchaseCannotBothWin() throws InterruptedException {
        Instant expiry = Instant.now();
        putSeat("A-1", STATUS_HELD, "hold-1", expiry);
        SeatHoldExpiredEvent hold = repository.findExpiredHolds(expiry).getFirst();

        // Both writers believe they are entitled to the seat, which is the real-world
        // clock-skew case: the cleanup's clock says the hold has expired (ttl <= now),
        // while the checkout service's clock is a few seconds behind and says it has not
        // (ttl > now). Both conditions evaluate true, so this is a genuine race rather
        // than one decided by construction.
        Instant purchaseClock = expiry.minus(Duration.ofSeconds(5));
        AtomicInteger reclaims = new AtomicInteger();
        AtomicInteger purchases = new AtomicInteger();

        race(100, i -> {
            if (i % 2 == 0) {
                if (repository.reclaim(hold, expiry)) {
                    reclaims.incrementAndGet();
                }
            } else if (tryConfirmPurchase("A-1", "hold-1", purchaseClock)) {
                purchases.incrementAndGet();
            }
        });

        assertThat(reclaims.get() + purchases.get())
                .as("the hold is resolved exactly once, by either the cleanup or the purchase")
                .isEqualTo(1);

        Map<String, AttributeValue> seat = getSeat("A-1");
        if (reclaims.get() == 1) {
            assertThat(seat.get(ATTR_STATUS).s()).isEqualTo(STATUS_AVAILABLE);
            assertThat(seat).doesNotContainKey(ATTR_HOLD_ID);
        } else {
            assertThat(seat.get(ATTR_STATUS).s()).isEqualTo(STATUS_SOLD);
            assertThat(seat.get(ATTR_HOLD_ID).s()).isEqualTo("hold-1");
        }
    }

    @Test
    void concurrentInvocationsReclaimEachSeatOnce() throws InterruptedException {
        Instant now = Instant.now();
        putSeat("A-1", STATUS_HELD, "hold-1", now.minus(Duration.ofSeconds(30)));
        SeatHoldExpiredEvent hold = repository.findExpiredHolds(now).getFirst();
        AtomicInteger reclaims = new AtomicInteger();

        // Two overlapping schedule ticks, or a retry racing the original, must not
        // double-reclaim — this is what lets the schedule run with retries disabled.
        race(50, i -> {
            if (repository.reclaim(hold, now)) {
                reclaims.incrementAndGet();
            }
        });

        assertThat(reclaims).hasValue(1);
    }

    private HoldCleanupHandler.Summary runCleanupAt(Instant now) {
        return new HoldCleanupHandler(repository, Clock.fixed(now, ZoneOffset.UTC))
                .handleRequest(Map.of(), null);
    }

    /** Mirrors {@code SeatRepository.holdSeat}; that class lives in another deployable. */
    private boolean tryHold(String seatId, String holdId, Instant expiresAt) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(TABLE)
                    .key(key(seatId))
                    .updateExpression("SET #status = :held, #holdId = :holdId, #ttl = :ttl")
                    .conditionExpression("#status = :available")
                    .expressionAttributeNames(Map.of(
                            "#status", ATTR_STATUS, "#holdId", ATTR_HOLD_ID, "#ttl", ATTR_HOLD_EXPIRES_AT))
                    .expressionAttributeValues(Map.of(
                            ":held", s(STATUS_HELD),
                            ":available", s(STATUS_AVAILABLE),
                            ":holdId", s(holdId),
                            ":ttl", n(expiresAt.getEpochSecond())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException lost) {
            return false;
        }
    }

    /** Mirrors {@code SeatRepository.confirmPurchase}, guard for guard. */
    private boolean tryConfirmPurchase(String seatId, String holdId, Instant now) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(TABLE)
                    .key(key(seatId))
                    .updateExpression("SET #status = :sold REMOVE #ttl")
                    .conditionExpression("#status = :held AND #holdId = :holdId AND #ttl > :now")
                    .expressionAttributeNames(Map.of(
                            "#status", ATTR_STATUS, "#holdId", ATTR_HOLD_ID, "#ttl", ATTR_HOLD_EXPIRES_AT))
                    .expressionAttributeValues(Map.of(
                            ":sold", s(STATUS_SOLD),
                            ":held", s(STATUS_HELD),
                            ":holdId", s(holdId),
                            ":now", n(now.getEpochSecond())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException lost) {
            return false;
        }
    }

    private void putSeat(String seatId, String status, String holdId, Instant expiresAt) {
        Map<String, AttributeValue> item = new HashMap<>(key(seatId));
        item.put(ATTR_EVENT_ID, s(EVENT_ID));
        item.put(ATTR_SEAT_ID, s(seatId));
        item.put(ATTR_STATUS, s(status));
        if (holdId != null) {
            item.put(ATTR_HOLD_ID, s(holdId));
        }
        if (expiresAt != null) {
            item.put(ATTR_HOLD_EXPIRES_AT, n(expiresAt.getEpochSecond()));
        }
        dynamo.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }

    private Map<String, AttributeValue> getSeat(String seatId) {
        return dynamo.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(key(seatId))
                .consistentRead(true)
                .build()).item();
    }

    private static Map<String, AttributeValue> key(String seatId) {
        return Map.of(ATTR_PK, s(pk(EVENT_ID)), ATTR_SK, s(sk(seatId)));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    /** Virtual threads released by a start gate at once, for maximum item contention. */
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
                        AttributeDefinition.builder().attributeName(SeatTableSchema.ATTR_PK)
                                .attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(SeatTableSchema.ATTR_SK)
                                .attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName(SeatTableSchema.ATTR_PK)
                                .keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(SeatTableSchema.ATTR_SK)
                                .keyType(KeyType.RANGE).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(TABLE));
    }
}
