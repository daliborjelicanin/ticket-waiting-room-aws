package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * What happened when a caller tried to claim the right to charge an order.
 *
 * <p>Sealed because the two cases must never be confused: one of them means "go call the
 * gateway", the other means "the money already moved, here is what happened". Making that
 * a compile-time distinction is cheaper than discovering it from a duplicated charge.
 */
public sealed interface AttemptClaim {

    /** The claim succeeded; this attempt owns the order and must now be sent to the gateway. */
    record Claimed(Payment payment) implements AttemptClaim { }

    /** This idempotency key has already been decided. Replay the original answer. */
    record AlreadySettled(ChargeOutcome outcome) implements AttemptClaim { }
}
