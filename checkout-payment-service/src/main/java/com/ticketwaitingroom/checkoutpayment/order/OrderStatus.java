package com.ticketwaitingroom.checkoutpayment.order;

/**
 * Lifecycle of an order. Terminal states are {@link #PAID}, {@link #FAILED} and
 * {@link #CANCELLED} — the saga's compensating action (releasing the seat holds) is
 * driven by an order reaching FAILED or CANCELLED, never by PENDING.
 */
public enum OrderStatus {

    /** Created, seats held, awaiting payment. */
    PENDING,

    /** Payment captured. The seats can now be confirmed as sold. */
    PAID,

    /** Payment declined or errored. The seat holds must be released. */
    FAILED,

    /** Abandoned before payment. The seat holds must be released. */
    CANCELLED
}
