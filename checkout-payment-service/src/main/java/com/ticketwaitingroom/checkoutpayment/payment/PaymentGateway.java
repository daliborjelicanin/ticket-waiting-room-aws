package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * The payment provider. Each operation returns a value-typed result rather than throwing:
 * a decline or a failed refund is an answer, and the row that claimed the attempt has to
 * record it.
 *
 * <p>Implementations must be idempotent on {@link GatewayCharge#idempotencyKey()} and
 * {@link GatewayRefund#idempotencyKey()}.
 */
public interface PaymentGateway {

    GatewayResult charge(GatewayCharge charge);

    RefundResult refund(GatewayRefund refund);
}
