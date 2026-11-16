package com.ticketwaitingroom.seatinventory.inventory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Seeds an event's seats so the hold/release/confirm flow has inventory to act on.
 * Re-runnable by design: seats that already exist are skipped (never overwritten),
 * counted separately so the caller can tell a fresh seed from a repeat. Every seat in one
 * call gets the same price; seed a price tier per call.
 */
@Service
@RequiredArgsConstructor
public class SeatSeedService {

    private final SeatRepository seatRepository;
    private final SeatInventoryMetrics metrics;

    /**
     * @throws IllegalArgumentException if {@code priceCents} is not positive: a free or
     *                                  negative seat would be charged as such by the saga
     */
    public SeedResult seedSeats(String eventId, List<String> seatIds, long priceCents) {
        if (priceCents <= 0) {
            throw new IllegalArgumentException("Seat price must be positive, was " + priceCents);
        }
        metrics.seatsSeeded(eventId);
        int created = 0;
        int skipped = 0;
        for (String seatId : seatIds) {
            if (seatRepository.createAvailableSeat(eventId, seatId, priceCents)) {
                created++;
            } else {
                skipped++;
            }
        }
        return new SeedResult(created, skipped);
    }

    public record SeedResult(int created, int skipped) { }
}
