package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * A charge request as the gateway sees it.
 *
 * @param idempotencyKey the key the gateway itself de-duplicates on — the same key must
 *                       never move money twice, however many times it is sent
 */
public record GatewayCharge(
        String idempotencyKey,
        String paymentToken,
        long amountCents,
        String currency
) {
}
