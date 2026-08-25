package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * The payment provider. Deliberately a single method with a value-typed result: an
 * implementation must never throw for a decline, because a decline is an answer and the
 * order has to record it.
 *
 * <p>Implementations must be idempotent on {@link GatewayCharge#idempotencyKey()}.
 */
public interface PaymentGateway {

    GatewayResult charge(GatewayCharge charge);
}
