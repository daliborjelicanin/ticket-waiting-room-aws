package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * Outcome of one refund attempt. {@link #PENDING} and {@link #SUCCEEDED} are the "live"
 * states that {@code ux_refunds_one_live_per_payment} allows one of per payment.
 */
public enum RefundStatus {

    /** Sent to the gateway, outcome not yet known. */
    PENDING,

    /** The money went back. */
    SUCCEEDED,

    /** The gateway did not complete it. Retryable under a new key, by an operator. */
    FAILED
}
