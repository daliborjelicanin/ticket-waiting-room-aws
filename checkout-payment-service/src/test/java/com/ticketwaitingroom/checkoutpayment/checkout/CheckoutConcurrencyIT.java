package com.ticketwaitingroom.checkoutpayment.checkout;

import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderSeat;
import com.ticketwaitingroom.checkoutpayment.order.OrderStatus;
import com.ticketwaitingroom.checkoutpayment.payment.ChargeOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.MockPaymentGateway;
import com.ticketwaitingroom.checkoutpayment.payment.Payment;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentGateway;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentRepository;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentStatus;
import com.ticketwaitingroom.common.exceptions.ChargeInProgressException;
import com.ticketwaitingroom.common.exceptions.NotYoursException;
import com.ticketwaitingroom.common.exceptions.OrderNotPayableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the transactional half of the system: an order is never charged twice, and money
 * moving is never separable from the order that records it.
 *
 * <p>Runs against a real Postgres (Testcontainers) because the guarantees under test are
 * the database's, not the application's — a partial unique index, a unique constraint, and
 * the atomicity of a commit. Mocking the repository would assert nothing about any of them.
 *
 * <p>Each test creates its own order, so tests are independent without truncating a shared
 * schema between them.
 */
@SpringBootTest
@Testcontainers
class CheckoutConcurrencyIT {

    /** Shaped like a Cognito {@code sub}, rather than a friendly label. */
    private static final String CUSTOMER = "b4c1e0a2-1111-4000-8000-000000000009";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private CheckoutService checkoutService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentGateway paymentGateway;

    private MockPaymentGateway gateway;

    @BeforeEach
    void resolveGateway() {
        gateway = (MockPaymentGateway) paymentGateway;
    }

    @Test
    void concurrentChargesWithDifferentKeysCaptureExactlyOnce() throws InterruptedException {
        Order order = anOrder();
        int attempts = 50;
        int before = gateway.distinctCharges();
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // Every thread brings its own idempotency key, so nothing de-duplicates them at the
        // key level. Only ux_payments_one_live_per_order stands between this and 50 charges.
        race(attempts, i -> {
            try {
                ChargeOutcome outcome = checkoutService.charge(
                        order.getId(), "key-" + order.getId() + "-" + i, MockPaymentGateway.TOKEN_OK);
                if (outcome.payment().isCaptured()) {
                    captured.incrementAndGet();
                }
            } catch (ChargeInProgressException | OrderNotPayableException lost) {
                rejected.incrementAndGet();
            }
        });

        assertThat(captured).as("exactly one attempt captures").hasValue(1);
        assertThat(rejected).hasValue(attempts - 1);
        assertThat(gateway.distinctCharges() - before)
                .as("the gateway is asked to move money exactly once")
                .isEqualTo(1);

        List<Payment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        assertThat(payments).hasSize(1);
        assertThat(payments.getFirst().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(checkoutService.findOrder(order.getId(), CUSTOMER).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void concurrentRetriesOfOneKeyChargeOnceAndAgreeOnTheResult() throws InterruptedException {
        Order order = anOrder();
        String sharedKey = "retry-" + order.getId();
        int attempts = 50;
        int before = gateway.distinctCharges();
        Queue<String> refs = new ConcurrentLinkedQueue<>();
        AtomicInteger inFlight = new AtomicInteger();

        // The saga retrying one step: same key every time. Retries must collapse onto the
        // original charge, and every caller that gets an answer must get the same answer.
        race(attempts, i -> {
            try {
                ChargeOutcome outcome = checkoutService.charge(
                        order.getId(), sharedKey, MockPaymentGateway.TOKEN_OK);
                refs.add(outcome.payment().getGatewayRef());
            } catch (ChargeInProgressException stillRunning) {
                inFlight.incrementAndGet();
            }
        });

        assertThat(gateway.distinctCharges() - before)
                .as("one key, one charge, however many retries")
                .isEqualTo(1);
        assertThat(paymentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())).hasSize(1);
        assertThat(refs).as("at least the winner got an answer").isNotEmpty();
        assertThat(Set.copyOf(refs))
                .as("every answer is the same answer")
                .hasSize(1);
        assertThat(refs.size() + inFlight.get()).isEqualTo(attempts);
    }

    @Test
    void capturedPaymentAndPaidOrderCommitTogether() {
        Order order = anOrder();

        ChargeOutcome outcome = checkoutService.charge(
                order.getId(), "atomic-" + order.getId(), MockPaymentGateway.TOKEN_OK);

        assertThat(outcome.payment().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outcome.payment().getGatewayRef()).isNotBlank();
        assertThat(outcome.order().getStatus()).isEqualTo(OrderStatus.PAID);
        // Re-read from the database: the pair must hold on disk, not just in the returned object.
        assertThat(checkoutService.findOrder(order.getId(), CUSTOMER).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void declineFailsTheOrderSoTheSagaReleasesTheSeats() {
        Order order = anOrder();

        ChargeOutcome outcome = checkoutService.charge(
                order.getId(), "declined-" + order.getId(), MockPaymentGateway.TOKEN_DECLINE);

        assertThat(outcome.payment().getStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(outcome.payment().getFailureReason()).isEqualTo("card_declined");
        assertThat(outcome.payment().getGatewayRef()).isNull();
        assertThat(outcome.order().getStatus())
                .as("a failed order is what triggers the compensating hold release")
                .isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void gatewayOutageFailsTheOrderAndIsDistinguishableFromADecline() {
        Order order = anOrder();

        ChargeOutcome outcome = checkoutService.charge(
                order.getId(), "errored-" + order.getId(), MockPaymentGateway.TOKEN_ERROR);

        assertThat(outcome.payment().getStatus())
                .as("FAILED, not DECLINED: the gateway never answered, so a retry is meaningful")
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(outcome.payment().getFailureReason()).isEqualTo("gateway_unavailable");
        assertThat(outcome.order().getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void replayingACompletedKeyReturnsTheOriginalResultWithoutCharging() {
        Order order = anOrder();
        String key = "replay-" + order.getId();
        ChargeOutcome first = checkoutService.charge(order.getId(), key, MockPaymentGateway.TOKEN_OK);
        int after = gateway.distinctCharges();

        ChargeOutcome replay = checkoutService.charge(order.getId(), key, MockPaymentGateway.TOKEN_OK);

        assertThat(replay.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(replay.payment().getGatewayRef()).isEqualTo(first.payment().getGatewayRef());
        assertThat(gateway.distinctCharges()).as("no second charge").isEqualTo(after);
        assertThat(paymentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())).hasSize(1);
    }

    @Test
    void aSettledOrderRejectsFurtherCharges() {
        Order order = anOrder();
        checkoutService.charge(order.getId(), "settled-" + order.getId(), MockPaymentGateway.TOKEN_OK);

        assertThatThrownBy(() -> checkoutService.charge(
                order.getId(), "second-" + order.getId(), MockPaymentGateway.TOKEN_OK))
                .isInstanceOf(OrderNotPayableException.class);

        assertThat(paymentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())).hasSize(1);
    }

    @Test
    void creatingAnOrderIsIdempotent() {
        String key = "order-key-" + UUID.randomUUID();

        Order first = checkoutService.createOrder(UUID.randomUUID(), "evt-1", CUSTOMER, 4500, key, seats());
        // A retry that lost its response and generated a new id: the idempotency key, not
        // the id, is what collapses the two onto one order.
        Order again = checkoutService.createOrder(UUID.randomUUID(), "evt-1", CUSTOMER, 4500, key, seats());

        assertThat(again.getId())
                .as("a retried create returns the original order, not a second payable one")
                .isEqualTo(first.getId());
    }

    @Test
    void cancellingAPendingOrderIsAllowedButCancellingAPaidOneIsNot() {
        Order pending = anOrder();
        assertThat(checkoutService.cancel(pending.getId(), CUSTOMER).getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Order paid = anOrder();
        checkoutService.charge(paid.getId(), "paid-" + paid.getId(), MockPaymentGateway.TOKEN_OK);
        assertThatThrownBy(() -> checkoutService.cancel(paid.getId(), CUSTOMER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherCustomersOrderIsReportedAsMissingRatherThanForbidden() {
        Order order = anOrder();

        // 404, not 403: a 403 would confirm the id is real, which is all an attacker needs
        // to walk the id space. The two answers must be indistinguishable.
        assertThatThrownBy(() -> checkoutService.findOrder(order.getId(), "somebody-else"))
                .isInstanceOf(NotYoursException.class);
        assertThatThrownBy(() -> checkoutService.cancel(order.getId(), "somebody-else"))
                .isInstanceOf(NotYoursException.class);
        assertThat(checkoutService.findOrder(order.getId(), CUSTOMER).getStatus())
                .as("and the owner is unaffected")
                .isEqualTo(OrderStatus.PENDING);
    }

    private Order anOrder() {
        return checkoutService.createOrder(UUID.randomUUID(), "evt-1", CUSTOMER, 4500,
                "order-" + UUID.randomUUID(), seats());
    }

    private static List<OrderSeat> seats() {
        return List.of(new OrderSeat("A-1", "hold-" + UUID.randomUUID()));
    }

    /**
     * Runs {@code attempts} virtual threads through {@code attempt}, released by a start
     * gate at once for maximum contention on the order.
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
            assertThat(done.await(60, TimeUnit.SECONDS)).as("all attempts finished").isTrue();
        }
    }
}
