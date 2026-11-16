package com.ticketwaitingroom.seatinventory.inventory;

import java.time.Instant;

/**
 * The result of successfully acquiring a hold on a seat. {@code priceCents} is the seat's
 * price as the winning write saw it — the amount the saga charges.
 */
public record SeatHold(
        String eventId,
        String seatId,
        String holdId,
        Instant expiresAt,
        long priceCents
) {
}
