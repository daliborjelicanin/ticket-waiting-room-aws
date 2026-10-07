package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * What the gateway said to a refund. Sealed for the same reason as {@link GatewayResult}:
 * every place that handles one must handle all of them.
 */
public sealed interface RefundResult {

    /** The money went back; {@code gatewayRef} is the provider's reference for the refund. */
    record Refunded(String gatewayRef) implements RefundResult { }

    /** The gateway did not complete the refund. The money is still taken: a human's problem. */
    record Failed(String reason) implements RefundResult { }
}
