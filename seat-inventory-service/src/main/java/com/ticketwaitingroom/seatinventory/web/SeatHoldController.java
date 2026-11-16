package com.ticketwaitingroom.seatinventory.web;

import com.ticketwaitingroom.seatinventory.inventory.SeatHold;
import com.ticketwaitingroom.seatinventory.inventory.SeatHoldService;
import com.ticketwaitingroom.seatinventory.inventory.SeatStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * HTTP surface for the seat-hold lifecycle. A successful hold returns 201; an
 * unavailable seat is mapped to 409 and a stale/expired hold to 404 by
 * {@link ApiExceptionHandler}.
 */
@RestController
@RequiredArgsConstructor
public class SeatHoldController {

    private final SeatHoldService seatHoldService;

    @PostMapping("/events/{eventId}/seats/{seatId}/hold")
    @ResponseStatus(HttpStatus.CREATED)
    public SeatHoldResponse hold(@PathVariable String eventId, @PathVariable String seatId) {
        SeatHold hold = seatHoldService.holdSeat(eventId, seatId);
        return new SeatHoldResponse(hold.eventId(), hold.seatId(), hold.holdId(), hold.expiresAt(), hold.priceCents());
    }

    @DeleteMapping("/events/{eventId}/seats/{seatId}/hold/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @PathVariable String eventId,
            @PathVariable String seatId,
            @PathVariable String holdId) {
        seatHoldService.releaseHold(eventId, seatId, holdId);
    }

    @PostMapping("/events/{eventId}/seats/{seatId}/purchase")
    public PurchaseResponse purchase(
            @PathVariable String eventId,
            @PathVariable String seatId,
            @RequestBody PurchaseRequest request) {
        seatHoldService.confirmPurchase(eventId, seatId, request.holdId());
        return new PurchaseResponse(eventId, seatId, SeatStatus.SOLD);
    }

    public record SeatHoldResponse(String eventId, String seatId, String holdId, Instant expiresAt, long priceCents) { }

    public record PurchaseRequest(String holdId) { }

    public record PurchaseResponse(String eventId, String seatId, SeatStatus status) { }
}
