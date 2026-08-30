package com.ticketwaitingroom.common.events;

/**
 * The body of a message on {@code ticket-issuance-queue}, written by the checkout saga's
 * {@code EnqueueTicketIssuance} state and read by {@code ticket-issuance-service}.
 *
 * <p>The field names are a wire contract with {@code docs/checkout-saga.asl.json}, which is
 * a JSON document no compiler checks. A rename on either side is not a compile error and
 * not a runtime error — it is a field that silently deserialises to {@code null} forever.
 * {@code TicketIssuanceContractTest} pins the two together, in the same spirit as
 * {@code SeatStatusSchemaContractTest}.
 *
 * @param orderId     the checkout order this ticket belongs to
 * @param eventId     the event the seat belongs to
 * @param seatId      the seat that was sold
 * @param customerRef who bought it
 * @param gatewayRef  the payment gateway's reference for the capture, carried for audit
 * @param amountCents what was charged, in integer minor units
 */
public record TicketIssuanceMessage(
        String orderId,
        String eventId,
        String seatId,
        String customerRef,
        String gatewayRef,
        long amountCents
) {
}
