package com.ticketwaitingroom.checkoutpayment.payment;

import com.ticketwaitingroom.checkoutpayment.order.Order;

/**
 * An order and the refund that settled it, as they stood at the end of one transaction. A
 * SUCCEEDED refund always accompanies a REFUNDED order.
 */
public record RefundOutcome(
        Order order,
        Refund refund
) {
}
