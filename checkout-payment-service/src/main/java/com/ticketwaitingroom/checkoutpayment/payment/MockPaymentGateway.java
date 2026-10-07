package com.ticketwaitingroom.checkoutpayment.payment;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for a real payment provider.
 *
 * <p>Two behaviors are modeled because both change how the rest of the system must be
 * written:
 *
 * <ul>
 *   <li><b>Magic tokens</b> give deterministic outcomes, so tests can assert on a decline
 *       or an outage without probability entering the suite. Anything else falls through
 *       to the configured decline/error rates, which is what a load test turns up.</li>
 *   <li><b>Idempotency</b>: a repeated {@code idempotencyKey} returns the <em>original</em>
 *       result and does not charge again. Real gateways behave this way, and a mock that
 *       does not let a double-charge bug pass the test suite and fail in production.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class MockPaymentGateway implements PaymentGateway {

    /** Always approved. */
    public static final String TOKEN_OK = "tok_ok";
    /** Always declined — the gateway's answer, not an error. */
    public static final String TOKEN_DECLINE = "tok_decline";
    /** Always a transport failure — retryable with the same key. */
    public static final String TOKEN_ERROR = "tok_error";
    /** Approved on charge; its refund fails. The case a human has to finish. */
    public static final String TOKEN_REFUND_ERROR = "tok_refund_error";

    private final PaymentGatewayProperties properties;
    private final Map<String, GatewayResult> resultsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, RefundResult> refundsByIdempotencyKey = new ConcurrentHashMap<>();
    /** The token each approved charge was made with, so a refund knows how to behave. */
    private final Map<String, String> tokensByChargeRef = new ConcurrentHashMap<>();

    @Override
    public GatewayResult charge(GatewayCharge charge) {
        // computeIfAbsent is what makes this atomic per key: concurrent retries of the same
        // key collapse onto one decision instead of racing to produce two.
        return resultsByIdempotencyKey.computeIfAbsent(charge.idempotencyKey(), key -> {
            sleepForConfiguredLatency();
            return decide(charge);
        });
    }

    /**
     * Idempotent on the refund's key in the same way as {@link #charge}. A refund of a charge
     * this gateway never approved fails, as a real provider's would.
     */
    @Override
    public RefundResult refund(GatewayRefund refund) {
        return refundsByIdempotencyKey.computeIfAbsent(refund.idempotencyKey(), key -> {
            sleepForConfiguredLatency();
            String token = tokensByChargeRef.get(refund.chargeRef());
            if (token == null) {
                return new RefundResult.Failed("unknown_charge");
            }
            if (TOKEN_REFUND_ERROR.equals(token)) {
                return new RefundResult.Failed("gateway_unavailable");
            }
            return new RefundResult.Refunded("mock_refund_" + UUID.randomUUID());
        });
    }

    /** How many distinct charges this gateway has actually decided, ignoring retries. */
    public int distinctCharges() {
        return resultsByIdempotencyKey.size();
    }

    /** How many distinct refunds this gateway has actually decided, ignoring retries. */
    public int distinctRefunds() {
        return refundsByIdempotencyKey.size();
    }

    private GatewayResult decide(GatewayCharge charge) {
        GatewayResult result = switch (charge.paymentToken()) {
            case TOKEN_OK, TOKEN_REFUND_ERROR -> approved();
            case TOKEN_DECLINE -> new GatewayResult.Declined("card_declined");
            case TOKEN_ERROR -> new GatewayResult.Failed("gateway_unavailable");
            default -> byConfiguredRates();
        };
        if (result instanceof GatewayResult.Approved approved) {
            tokensByChargeRef.put(approved.gatewayRef(), charge.paymentToken());
        }
        return result;
    }

    private GatewayResult byConfiguredRates() {
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < properties.errorRate()) {
            return new GatewayResult.Failed("gateway_unavailable");
        }
        if (roll < properties.errorRate() + properties.declineRate()) {
            return new GatewayResult.Declined("card_declined");
        }
        return approved();
    }

    private static GatewayResult approved() {
        return new GatewayResult.Approved("mock_" + UUID.randomUUID());
    }

    private void sleepForConfiguredLatency() {
        Duration latency = properties.latency();
        if (latency == null || latency.isZero() || latency.isNegative()) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
