package com.ticketwaitingroom.checkoutpayment.payment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway =
            new MockPaymentGateway(new PaymentGatewayProperties(0.0, 0.0, Duration.ZERO));

    @Test
    void magicTokensGiveDeterministicOutcomes() {
        assertThat(charge("k1", MockPaymentGateway.TOKEN_OK)).isInstanceOf(GatewayResult.Approved.class);
        assertThat(charge("k2", MockPaymentGateway.TOKEN_DECLINE)).isInstanceOf(GatewayResult.Declined.class);
        assertThat(charge("k3", MockPaymentGateway.TOKEN_ERROR)).isInstanceOf(GatewayResult.Failed.class);
    }

    @Test
    void approvalCarriesAGatewayReference() {
        GatewayResult result = charge("k1", MockPaymentGateway.TOKEN_OK);

        assertThat(result).isInstanceOfSatisfying(GatewayResult.Approved.class,
                approved -> assertThat(approved.gatewayRef()).startsWith("mock_"));
    }

    @Test
    void repeatingAKeyReturnsTheOriginalResultAndDoesNotChargeAgain() {
        GatewayResult first = charge("same-key", MockPaymentGateway.TOKEN_OK);
        GatewayResult second = charge("same-key", MockPaymentGateway.TOKEN_OK);

        assertThat(second).isEqualTo(first);
        assertThat(gateway.distinctCharges()).isEqualTo(1);
    }

    @Test
    void aRepeatedKeyIgnoresADifferentTokenRatherThanReDeciding() {
        GatewayResult approved = charge("same-key", MockPaymentGateway.TOKEN_OK);

        // A real gateway keys off the idempotency key, not the payload. Re-deciding here
        // would let a retry quietly turn a captured charge into a declined one.
        assertThat(charge("same-key", MockPaymentGateway.TOKEN_DECLINE)).isEqualTo(approved);
        assertThat(gateway.distinctCharges()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfOneKeyDecideExactlyOnce() throws InterruptedException {
        int callers = 64;
        var results = new ConcurrentLinkedQueue<GatewayResult>();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        results.add(charge("hot-key", MockPaymentGateway.TOKEN_OK));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(gateway.distinctCharges()).isEqualTo(1);
        assertThat(Set.copyOf(results)).as("every caller saw the same decision").hasSize(1);
    }

    @Test
    void aFullDeclineRateDeclinesUnknownTokens() {
        var alwaysDecline = new MockPaymentGateway(
                new PaymentGatewayProperties(1.0, 0.0, Duration.ZERO));

        assertThat(alwaysDecline.charge(new GatewayCharge("k", "tok_visa_4242", 100, "EUR")))
                .isInstanceOf(GatewayResult.Declined.class);
    }

    @Test
    void aFullErrorRateFailsUnknownTokens() {
        var alwaysError = new MockPaymentGateway(
                new PaymentGatewayProperties(0.0, 1.0, Duration.ZERO));

        assertThat(alwaysError.charge(new GatewayCharge("k", "tok_visa_4242", 100, "EUR")))
                .isInstanceOf(GatewayResult.Failed.class);
    }

    @Test
    void ratesDoNotOverrideMagicTokens() {
        var alwaysError = new MockPaymentGateway(
                new PaymentGatewayProperties(0.0, 1.0, Duration.ZERO));

        assertThat(alwaysError.charge(new GatewayCharge("k", MockPaymentGateway.TOKEN_OK, 100, "EUR")))
                .as("deterministic tokens must stay deterministic under chaos settings")
                .isInstanceOf(GatewayResult.Approved.class);
    }

    @Test
    void aRefundOfAnApprovedChargeSucceedsOnceHoweverOftenItIsRetried() {
        String chargeRef = ((GatewayResult.Approved) charge("k1", MockPaymentGateway.TOKEN_OK)).gatewayRef();

        RefundResult first = refund("r1", chargeRef);
        RefundResult retry = refund("r1", chargeRef);

        assertThat(first).isInstanceOfSatisfying(RefundResult.Refunded.class,
                refunded -> assertThat(refunded.gatewayRef()).startsWith("mock_refund_"));
        assertThat(retry).as("a retried key replays, it does not refund again").isEqualTo(first);
        assertThat(gateway.distinctRefunds()).isEqualTo(1);
    }

    @Test
    void theRefundErrorTokenApprovesTheChargeAndFailsItsRefund() {
        GatewayResult charged = charge("k1", MockPaymentGateway.TOKEN_REFUND_ERROR);
        assertThat(charged).isInstanceOf(GatewayResult.Approved.class);

        assertThat(refund("r1", ((GatewayResult.Approved) charged).gatewayRef()))
                .isEqualTo(new RefundResult.Failed("gateway_unavailable"));
    }

    @Test
    void aRefundOfAChargeTheGatewayNeverMadeFails() {
        assertThat(refund("r1", "mock_never_issued")).isEqualTo(new RefundResult.Failed("unknown_charge"));
    }

    private RefundResult refund(String idempotencyKey, String chargeRef) {
        return gateway.refund(new GatewayRefund(idempotencyKey, chargeRef, 4500, "EUR"));
    }

    private GatewayResult charge(String idempotencyKey, String token) {
        return gateway.charge(new GatewayCharge(idempotencyKey, token, 4500, "EUR"));
    }
}
