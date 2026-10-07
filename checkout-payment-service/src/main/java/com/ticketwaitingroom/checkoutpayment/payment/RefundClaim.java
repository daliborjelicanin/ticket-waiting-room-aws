package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * What happened when a caller tried to claim the right to refund an order. The refund
 * counterpart of {@link AttemptClaim}, sealed for the same reason: "go call the gateway"
 * and "the money already went back" must never be confused.
 */
public sealed interface RefundClaim {

    /**
     * The claim succeeded; this refund must now be sent to the gateway.
     *
     * @param chargeRef the gateway's reference for the capture being reversed
     */
    record Claimed(Refund refund, String chargeRef) implements RefundClaim { }

    /** This idempotency key has already been decided. Replay the original answer. */
    record AlreadySettled(RefundOutcome outcome) implements RefundClaim { }
}
