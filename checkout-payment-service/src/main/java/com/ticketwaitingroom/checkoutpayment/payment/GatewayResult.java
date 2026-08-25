package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * What the payment gateway said. Sealed so that handling it is exhaustive: adding a new
 * outcome becomes a compile error at every decision point rather than a silently skipped
 * branch in the one place money changes hands.
 */
public sealed interface GatewayResult {

    /** The charge succeeded; {@code gatewayRef} is the provider's reference for it. */
    record Approved(String gatewayRef) implements GatewayResult { }

    /** The gateway refused the charge. Retrying the same instrument will not help. */
    record Declined(String reason) implements GatewayResult { }

    /** The gateway did not answer. Retryable with the same idempotency key. */
    record Failed(String reason) implements GatewayResult { }
}
