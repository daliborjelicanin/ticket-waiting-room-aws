package com.ticketwaitingroom.seatinventory.inventory;

import com.ticketwaitingroom.common.exceptions.HoldNotFoundException;
import com.ticketwaitingroom.common.exceptions.SeatUnavailableException;
import com.ticketwaitingroom.common.inventory.SeatTableSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the project's centerpiece: atomic, race-free seat locking.
 *
 * <p>Runs against a real DynamoDB (LocalStack in Docker) so the concurrency guarantee is
 * exercised end-to-end, not mocked. Many virtual threads race the <em>same</em> seat
 * through its transitions (hold, confirm, release); the DynamoDB
 * {@code ConditionExpression}s in {@link SeatRepository} must let exactly one win each
 * race — a double-sold seat here is the exact bug this project exists to rule out.
 */
@Testcontainers
class SeatHoldConcurrencyIT {

    private static final String TABLE = "seat-inventory";
    private static final String EVENT_ID = "evt-1";
    private static final String SEAT_ID = "A-12";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"));

    private static DynamoDbClient dynamo;
    private SeatRepository repository;

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
    void freshTableWithOneAvailableSeat() {
        recreateTable();
        repository = new SeatRepository(dynamo, TABLE);
        repository.createAvailableSeat(EVENT_ID, SEAT_ID);
    }

    @Test
    void exactlyOneConcurrentHoldWins() throws InterruptedException {
        int attempts = 100;
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> winningHoldId = new AtomicReference<>();

        race(attempts, i -> {
            try {
                repository.holdSeat(EVENT_ID, SEAT_ID, "hold-" + i, expiresAt);
                successes.incrementAndGet();
                winningHoldId.set("hold-" + i);
            } catch (SeatUnavailableException lost) {
                failures.incrementAndGet();
            }
        });

        assertThat(successes).as("exactly one hold succeeds").hasValue(1);
        assertThat(failures).as("every other attempt is rejected").hasValue(attempts - 1);

        Seat seat = repository.getSeat(EVENT_ID, SEAT_ID);
        assertThat(seat).isNotNull();
        assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.holdId()).isEqualTo(winningHoldId.get());
    }

    @Test
    void exactlyOneDuplicateConfirmWins() throws InterruptedException {
        int attempts = 100;
        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-1", Instant.now().plus(Duration.ofMinutes(5)));
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        // duplicate/retried confirm requests for the same hold must sell the seat once
        race(attempts, i -> {
            try {
                repository.confirmPurchase(EVENT_ID, SEAT_ID, "hold-1", Instant.now());
                successes.incrementAndGet();
            } catch (HoldNotFoundException lost) {
                failures.incrementAndGet();
            }
        });

        assertThat(successes).as("exactly one confirm succeeds").hasValue(1);
        assertThat(failures).hasValue(attempts - 1);

        Seat seat = repository.getSeat(EVENT_ID, SEAT_ID);
        assertThat(seat).isNotNull();
        assertThat(seat.status()).isEqualTo(SeatStatus.SOLD);
        assertThat(seat.holdId()).isEqualTo("hold-1");
        assertThat(seat.holdExpiresAt()).as("TTL removed so a SOLD seat can never be TTL-deleted").isNull();
    }

    @Test
    void releaseAndConfirmCannotBothWin() throws InterruptedException {
        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-1", Instant.now().plus(Duration.ofMinutes(5)));
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger confirms = new AtomicInteger();

        race(100, i -> {
            try {
                if (i % 2 == 0) {
                    repository.releaseHold(EVENT_ID, SEAT_ID, "hold-1");
                    releases.incrementAndGet();
                } else {
                    repository.confirmPurchase(EVENT_ID, SEAT_ID, "hold-1", Instant.now());
                    confirms.incrementAndGet();
                }
            } catch (HoldNotFoundException lost) {
                // expected for every attempt after the first winner
            }
        });

        assertThat(releases.get() + confirms.get())
                .as("the hold is consumed exactly once, by either a release or a confirm")
                .isEqualTo(1);

        Seat seat = repository.getSeat(EVENT_ID, SEAT_ID);
        assertThat(seat).isNotNull();
        if (releases.get() == 1) {
            assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.holdId()).isNull();
        } else {
            assertThat(seat.status()).isEqualTo(SeatStatus.SOLD);
        }
    }

    @Test
    void expiredHoldCannotBeConfirmed() {
        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-1", Instant.now().minus(Duration.ofSeconds(30)));

        assertThatThrownBy(() -> repository.confirmPurchase(EVENT_ID, SEAT_ID, "hold-1", Instant.now()))
                .isInstanceOf(HoldNotFoundException.class);

        Seat seat = repository.getSeat(EVENT_ID, SEAT_ID);
        assertThat(seat).isNotNull();
        assertThat(seat.status()).as("reclaiming the expired hold is the cleanup slice's job").isEqualTo(SeatStatus.HELD);
    }

    @Test
    void releaseRequiresTheOwningHoldId() {
        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-1", Instant.now().plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> repository.releaseHold(EVENT_ID, SEAT_ID, "someone-elses-hold"))
                .isInstanceOf(HoldNotFoundException.class);

        Seat seat = repository.getSeat(EVENT_ID, SEAT_ID);
        assertThat(seat).isNotNull();
        assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.holdId()).isEqualTo("hold-1");
    }

    @Test
    void releasedSeatCanBeHeldAgain() {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-1", expiresAt);
        repository.releaseHold(EVENT_ID, SEAT_ID, "hold-1");

        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-2", expiresAt);

        Seat seat = repository.getSeat(EVENT_ID, SEAT_ID);
        assertThat(seat).isNotNull();
        assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.holdId()).isEqualTo("hold-2");
    }

    @Test
    void remainingCountFollowsTheSeatLifecycle() {
        repository.createAvailableSeat(EVENT_ID, "A-13");
        repository.createAvailableSeat(EVENT_ID, "A-14");
        repository.createAvailableSeat("evt-other", "Z-1");
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));

        assertThat(repository.countAvailable(EVENT_ID)).isEqualTo(3);

        repository.holdSeat(EVENT_ID, SEAT_ID, "hold-1", expiresAt);
        repository.holdSeat(EVENT_ID, "A-13", "hold-2", expiresAt);
        repository.confirmPurchase(EVENT_ID, "A-13", "hold-2", Instant.now());
        assertThat(repository.countAvailable(EVENT_ID)).isEqualTo(1);

        repository.releaseHold(EVENT_ID, SEAT_ID, "hold-1");
        assertThat(repository.countAvailable(EVENT_ID)).isEqualTo(2);
        // Another event's seats never count towards this one's: the query is keyed, not scanned.
        assertThat(repository.countAvailable("evt-other")).isEqualTo(1);
        assertThat(repository.countAvailable("evt-unknown")).isZero();
    }

    /**
     * Runs {@code attempts} virtual threads through {@code attempt}, released by a start
     * gate at once for maximum contention on the seat item.
     */
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

        dynamo.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                .tableName(TABLE)
                .timeToLiveSpecification(TimeToLiveSpecification.builder()
                        .enabled(true)
                        .attributeName(SeatTableSchema.ATTR_HOLD_EXPIRES_AT)
                        .build())
                .build());
    }
}
