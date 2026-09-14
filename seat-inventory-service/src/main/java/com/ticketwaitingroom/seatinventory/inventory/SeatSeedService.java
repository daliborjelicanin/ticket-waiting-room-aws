package com.ticketwaitingroom.seatinventory.inventory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Seeds an event's seats so the hold/release/confirm flow has inventory to act on.
 * Re-runnable by design: seats that already exist are skipped (never overwritten),
 * counted separately so the caller can tell a fresh seed from a repeat.
 */
@Service
@RequiredArgsConstructor
public class SeatSeedService {

    private final SeatRepository seatRepository;
    private final SeatInventoryMetrics metrics;

    public SeedResult seedSeats(String eventId, List<String> seatIds) {
        metrics.seatsSeeded(eventId);
        int created = 0;
        int skipped = 0;
        for (String seatId : seatIds) {
            if (seatRepository.createAvailableSeat(eventId, seatId)) {
                created++;
            } else {
                skipped++;
            }
        }
        return new SeedResult(created, skipped);
    }

    public record SeedResult(int created, int skipped) { }
}
