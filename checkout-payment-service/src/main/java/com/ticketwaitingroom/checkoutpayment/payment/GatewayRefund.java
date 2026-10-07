package com.ticketwaitingroom.checkoutpayment.payment;

/**
 * A refund request as the gateway sees it.
 *
 * @param idempotencyKey the key the gateway de-duplicates on — a retried refund must never
 *                       return money twice
 * @param chargeRef      the gateway's reference for the capture being reversed
 */
public record GatewayRefund(
        String idempotencyKey,
        String chargeRef,
        long amountCents,
        String currency
) {
}
