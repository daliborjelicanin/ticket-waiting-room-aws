package com.ticketwaitingroom.checkoutpayment.payment;

import com.ticketwaitingroom.checkoutpayment.order.Order;

/**
 * An order and the payment attempt that settled it, as they stood at the end of one
 * transaction. Returned together because the pair is the invariant worth observing: a
 * CAPTURED payment always accompanies a PAID order, never a PENDING one.
 */
public record ChargeOutcome(
        Order order,
        Payment payment
) {
}
