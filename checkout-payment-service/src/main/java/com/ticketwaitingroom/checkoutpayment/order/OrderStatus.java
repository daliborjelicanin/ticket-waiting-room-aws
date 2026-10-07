package com.ticketwaitingroom.checkoutpayment.order;

/**
 * Lifecycle of an order. Terminal states are {@link #FAILED}, {@link #CANCELLED} and
 * {@link #REFUNDED}; {@link #PAID} is terminal unless the seat cannot be confirmed. The
 * saga's compensating action is a hold release before money moves (FAILED, CANCELLED) and a
 * refund after it (PAID → REFUNDED), never the other way round.
 */
public enum OrderStatus {

    /** Created, seats held, awaiting payment. */
    PENDING,

    /** Payment captured. The seats can now be confirmed as sold. */
    PAID,

    /** Payment declined or errored. The seat holds must be released. */
    FAILED,

    /** Abandoned before payment. The seat holds must be released. */
    CANCELLED,

    /**
     * Paid, then refunded because the seat could not be confirmed as sold to this order.
     * Only ever reached from PAID.
     */
    REFUNDED
}
