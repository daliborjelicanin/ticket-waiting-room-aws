package com.ticketwaitingroom.seatinventory.web;

import com.ticketwaitingroom.seatinventory.inventory.SeatSeedService;
import com.ticketwaitingroom.seatinventory.inventory.SeatSeedService.SeedResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin endpoint to seed an event's seats. Under {@code /admin} because it is an
 * operator action, not part of the customer purchase flow — later slices put it behind
 * different auth/routing than the public endpoints.
 */
@RestController
@RequiredArgsConstructor
public class SeatSeedController {

    private final SeatSeedService seatSeedService;

    @PostMapping("/admin/events/{eventId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    public SeedResponse seed(@PathVariable String eventId, @RequestBody SeedRequest request) {
        SeedResult result = seatSeedService.seedSeats(eventId, request.seatIds(), request.priceCents());
        return new SeedResponse(eventId, result.created(), result.skipped());
    }

    public record SeedRequest(List<String> seatIds, long priceCents) { }

    public record SeedResponse(String eventId, int created, int skipped) { }
}
