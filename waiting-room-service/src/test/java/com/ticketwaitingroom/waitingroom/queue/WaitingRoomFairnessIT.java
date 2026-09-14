package com.ticketwaitingroom.waitingroom.queue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the waiting room's fairness guarantee: first in, first admitted, under
 * concurrent joins and concurrent admitters.
 *
 * <p>Runs against a real Redis (Testcontainers) rather than a mock, because the whole
 * guarantee lives in Redis semantics — the atomicity of {@code INCR}, of {@code ZPOPMIN},
 * of {@code HSETNX}, and of a Lua script as a unit. A mocked client would assert nothing
 * about any of them.
 *
 * <p>The two tests that matter most sit next to each other and say opposite things:
 * {@link #concurrentJoinersGetDistinctContiguousPlaces} proves 200 people racing get 200
 * distinct places, and {@link #concurrentJoinsByOneIdentityYieldOnePlace} proves 200
 * threads racing as <em>one</em> person get exactly one. Either alone is a half-truth.
 *
 * <p>Each test gets its own event id, so the per-event keys never collide and the tests
 * stay independent without flushing a shared server.
 */
@Testcontainers
class WaitingRoomFairnessIT {

    private static final AtomicInteger EVENT_SEQ = new AtomicInteger();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static WaitingRoomQueue queue;

    private String eventId;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        queue = new WaitingRoomQueue(template, listScript("redis/join.lua"),
                listScript("redis/position.lua"), listScript("redis/admit.lua"), 3600);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void freshEvent() {
        eventId = "evt-" + EVENT_SEQ.incrementAndGet();
    }

    @Test
    void concurrentJoinersGetDistinctContiguousPlaces() throws InterruptedException {
        int joiners = 200;
        Instant now = Instant.now();
        QueuePlace[] places = new QueuePlace[joiners];

        race(joiners, i -> places[i] = queue.join(eventId, "cust-" + i, "t-" + i, now));

        assertThat(places).allSatisfy(place -> assertThat(place.status()).isEqualTo(QueueStatus.WAITING));
        assertThat(places)
                .as("every concurrent joiner gets its own place -- no two share a ticket number")
                .extracting(QueuePlace::position)
                .containsExactlyInAnyOrderElementsOf(
                        IntStream.rangeClosed(1, joiners).mapToObj(Long::valueOf).toList());
        assertThat(queue.place(eventId, "cust-0", now).queueLength()).isEqualTo(joiners);
    }

    @Test
    void admissionOrderMatchesThePlacesHandedOutUnderConcurrentJoins() throws InterruptedException {
        int joiners = 200;
        Instant now = Instant.now();
        QueuePlace[] places = new QueuePlace[joiners];

        race(joiners, i -> places[i] = queue.join(eventId, "cust-" + i, "t-" + i, now));

        // Whatever order the threads actually reached Redis in, each was told a place.
        // Admission must honour exactly that, or the number shown to a user is a lie.
        List<String> expected = Arrays.stream(places)
                .sorted(Comparator.comparing(QueuePlace::position))
                .map(QueuePlace::token)
                .toList();

        List<String> admitted = drain(now, 13);

        assertThat(admitted).containsExactlyElementsOf(expected);
    }

    @Test
    void admissionIsFifoAcrossBatches() {
        Instant now = Instant.now();
        List<String> joinOrder = IntStream.range(0, 50).mapToObj(i -> "t-" + i).toList();
        joinOrder.forEach(token -> queue.join(eventId, "cust-" + token, token, now));

        assertThat(drain(now, 7))
                .as("batch boundaries must not reorder or drop anyone")
                .containsExactlyElementsOf(joinOrder);
    }

    @Test
    void concurrentAdmittersNeverAdmitTheSameTokenTwice() throws InterruptedException {
        int joiners = 120;
        int admitters = 12;
        Instant now = Instant.now();
        IntStream.range(0, joiners).forEach(i -> queue.join(eventId, "cust-" + i, "t-" + i, now));

        Queue<String> admitted = new ConcurrentLinkedQueue<>();
        // Between them the admitters ask for far more than the queue holds, so they race
        // both each other and the end of the queue.
        race(admitters, i -> admitted.addAll(queue.admitNext(eventId, 20, now.plusSeconds(600), now)));

        assertThat(admitted).as("every token admitted exactly once").doesNotHaveDuplicates();
        assertThat(admitted).hasSize(joiners);
        assertThat(queue.place(eventId, "cust-0", now).queueLength()).isZero();
    }

    @Test
    void concurrentJoinsByOneIdentityYieldOnePlace() throws InterruptedException {
        int attempts = 200;
        Instant now = Instant.now();
        QueuePlace[] places = new QueuePlace[attempts];

        // Every thread offers a different candidate token, which is exactly what a client
        // looping the join endpoint would do. Only one candidate may ever be claimed.
        race(attempts, i -> places[i] = queue.join(eventId, "cust-1", "candidate-" + i, now));

        assertThat(places)
                .as("one person holds one place, however hard they race themselves")
                .allSatisfy(place -> assertThat(place.position()).isEqualTo(1L));
        assertThat(places)
                .extracting(QueuePlace::token)
                .as("and every attempt is told the same token")
                .containsOnly(places[0].token());
        assertThat(queue.place(eventId, "cust-1", now).queueLength()).isEqualTo(1);
    }

    @Test
    void oneIdentityCannotOutnumberTheRoom() throws InterruptedException {
        Instant now = Instant.now();
        // A scalper hammering the join endpoint against nine ordinary people. Without the
        // identity claim the queue would be 209 long and 200 of the places would be theirs.
        race(200, i -> queue.join(eventId, "scalper", "candidate-" + i, now));
        IntStream.range(0, 9).forEach(i -> queue.join(eventId, "cust-" + i, "t-" + i, now));

        assertThat(queue.place(eventId, "scalper", now).queueLength())
                .as("ten people in the room, not two hundred and nine")
                .isEqualTo(10);
    }

    @Test
    void rejoiningKeepsTheOriginalPlaceWithoutTheClientRememberingAnything() {
        Instant now = Instant.now();
        QueuePlace first = queue.join(eventId, "cust-1", "candidate-a", now);
        queue.join(eventId, "cust-2", "t-2", now);

        // A different candidate token, as a client that lost its response would send.
        QueuePlace rejoined = queue.join(eventId, "cust-1", "candidate-b", now);

        assertThat(rejoined.position()).as("a retried join must not cost the client its place").isEqualTo(1L);
        assertThat(rejoined.token())
                .as("identity, not the token, is what makes the join idempotent")
                .isEqualTo(first.token());
        assertThat(rejoined.queueLength()).isEqualTo(2);
    }

    @Test
    void positionFallsAsThePeopleAheadAreAdmitted() {
        Instant now = Instant.now();
        IntStream.range(0, 10).forEach(i -> queue.join(eventId, "cust-" + i, "t-" + i, now));
        assertThat(queue.place(eventId, "cust-9", now).position()).isEqualTo(10L);

        queue.admitNext(eventId, 4, now.plusSeconds(600), now);

        QueuePlace place = queue.place(eventId, "cust-9", now);
        assertThat(place.position()).isEqualTo(6L);
        assertThat(place.queueLength()).isEqualTo(6);
    }

    @Test
    void sizesReadTheTwoSetsTheGaugesReport() {
        Instant now = Instant.now();
        assertThat(queue.sizes(eventId)).isEqualTo(new WaitingRoomQueue.Sizes(0, 0));
        IntStream.range(0, 10).forEach(i -> queue.join(eventId, "cust-" + i, "t-" + i, now));
        assertThat(queue.sizes(eventId)).isEqualTo(new WaitingRoomQueue.Sizes(10, 0));

        queue.admitNext(eventId, 4, now.plusSeconds(600), now);

        assertThat(queue.sizes(eventId)).isEqualTo(new WaitingRoomQueue.Sizes(6, 4));
    }

    @Test
    void admittedCustomerLeavesTheQueueAndCarriesAPass() {
        Instant now = Instant.now();
        Instant passExpiry = now.plusSeconds(600);
        queue.join(eventId, "cust-1", "t-1", now);

        queue.admitNext(eventId, 1, passExpiry, now);

        QueuePlace place = queue.place(eventId, "cust-1", now);
        assertThat(place.status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(place.token()).as("still resolvable by identity once out of the queue").isEqualTo("t-1");
        assertThat(place.position()).isNull();
        assertThat(place.queueLength()).isZero();
        assertThat(place.admissionExpiresAt()).isEqualTo(passExpiry.truncatedTo(ChronoUnit.SECONDS));

        QueuePlace rejoined = queue.join(eventId, "cust-1", "candidate-b", now);
        assertThat(rejoined.status())
                .as("re-joining while admitted must not put the customer back in line")
                .isEqualTo(QueueStatus.ADMITTED);
        assertThat(rejoined.token())
                .as("nor mint a second token, which would strand the pass on the first")
                .isEqualTo("t-1");
    }

    @Test
    void lapsedPassIsReportedThenReclaimedOnTheNextAdmit() {
        Instant now = Instant.now();
        queue.join(eventId, "cust-1", "t-1", now);
        queue.admitNext(eventId, 1, now.plusSeconds(60), now);
        Instant afterLapse = now.plusSeconds(61);

        assertThat(queue.place(eventId, "cust-1", afterLapse).status()).isEqualTo(QueueStatus.EXPIRED);

        queue.join(eventId, "cust-2", "t-2", afterLapse);
        assertThat(queue.admitNext(eventId, 5, afterLapse.plusSeconds(60), afterLapse)).containsExactly("t-2");
        assertThat(queue.place(eventId, "cust-1", afterLapse).status())
                .as("the abandoned slot is returned to the room")
                .isEqualTo(QueueStatus.NOT_QUEUED);
    }

    @Test
    void customerWithALapsedPassRejoinsFromTheBackUnderTheSameToken() {
        Instant now = Instant.now();
        queue.join(eventId, "cust-1", "t-1", now);
        queue.admitNext(eventId, 1, now.plusSeconds(60), now);
        Instant afterLapse = now.plusSeconds(61);
        queue.join(eventId, "cust-2", "t-2", afterLapse);

        QueuePlace rejoined = queue.join(eventId, "cust-1", "candidate-b", afterLapse);

        assertThat(rejoined.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(rejoined.position())
                .as("a forfeited slot does not buy back the original place")
                .isEqualTo(2L);
        assertThat(rejoined.token())
                .as("a lapsed pass must not be a way to acquire a second token")
                .isEqualTo("t-1");
    }

    @Test
    void unknownCustomerIsNotQueued() {
        QueuePlace place = queue.place(eventId, "never-seen", Instant.now());

        assertThat(place.status()).isEqualTo(QueueStatus.NOT_QUEUED);
        assertThat(place.position()).isNull();
        assertThat(place.token()).isNull();
    }

    @Test
    void admittingAnEmptyQueueYieldsNobody() {
        assertThat(queue.admitNext(eventId, 10, Instant.now().plusSeconds(600), Instant.now())).isEmpty();
    }

    /** Admits the whole queue in batches of {@code batchSize}, in order. */
    private List<String> drain(Instant now, int batchSize) {
        List<String> admitted = new ArrayList<>();
        List<String> batch;
        do {
            batch = queue.admitNext(eventId, batchSize, now.plusSeconds(600), now);
            admitted.addAll(batch);
        } while (!batch.isEmpty());
        return admitted;
    }

    /**
     * Runs {@code attempts} virtual threads through {@code attempt}, released by a start
     * gate at once for maximum contention on the queue.
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

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> listScript(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }
}
