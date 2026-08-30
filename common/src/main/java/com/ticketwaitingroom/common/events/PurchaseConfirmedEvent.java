package com.ticketwaitingroom.common.events;

/**
 * The message the saga's {@code NotifyPurchaseConfirmed} state publishes to the
 * {@code purchase-confirmed} SNS topic, and which {@code notification-service} reads off
 * the SQS queue subscribed to that topic.
 *
 * <p>Same field names as {@link TicketIssuanceMessage} deliberately: both describe the same
 * completed purchase, and giving them one vocabulary means a reader of either queue is
 * looking at the same shape. They stay separate types because they are separate contracts —
 * the notification payload can gain a locale or a template id without touching issuance.
 *
 * <p>The SQS subscription must have <strong>raw message delivery enabled</strong>, or every
 * body arrives wrapped in SNS's envelope and this record deserialises to all-nulls.
 */
public record PurchaseConfirmedEvent(
        String orderId,
        String eventId,
        String seatId,
        String customerRef,
        String gatewayRef,
        long amountCents
) {
}
