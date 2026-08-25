package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * Outcome of one payment attempt.
 *
 * <p>The distinction between {@link #DECLINED} and {@link #FAILED} is the one that matters
 * operationally: a decline is the gateway's answer and retrying the same card will get the
 * same answer, while a failure is the gateway not answering, which is retryable. Only
 * {@link #PENDING} and {@link #CAPTURED} count as "live" for the partial unique index that
 * limits an order to one attempt at a time.
 */
public enum PaymentStatus {

    /** Sent to the gateway, outcome not yet known. */
    PENDING,

    /** The gateway took the money. */
    CAPTURED,

    /** The gateway refused. A business answer, not an error. */
    DECLINED,

    /** The gateway could not be reached or errored. Retryable. */
    FAILED
}
