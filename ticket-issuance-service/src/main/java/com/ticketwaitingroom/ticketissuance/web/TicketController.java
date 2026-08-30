package com.ticketwaitingroom.ticketissuance.web;

import com.ticketwaitingroom.ticketissuance.qr.TicketQrStore;
import com.ticketwaitingroom.ticketissuance.ticket.Ticket;
import com.ticketwaitingroom.ticketissuance.ticket.TicketIssuanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only HTTP surface. Tickets are created by the SQS consumer, never by a request —
 * there is deliberately no POST here, because an endpoint that issues a ticket would be a
 * second path to the same row that bypasses the saga entirely.
 */
@RestController
@RequiredArgsConstructor
public class TicketController {

    private final TicketIssuanceService issuanceService;
    private final TicketQrStore qrStore;

    @GetMapping("/orders/{orderId}/tickets")
    public List<TicketResponse> ticketsForOrder(@PathVariable UUID orderId) {
        return issuanceService.findByOrder(orderId).stream().map(TicketResponse::of).toList();
    }

    /**
     * A download URL for the ticket's QR image.
     *
     * <p>200 with {@code PENDING} rather than a 404 when the image is not there yet: the
     * ticket exists and is valid, only its rendering is still in flight behind a queue. A
     * 404 would tell a client the ticket is wrong, and a 202 would imply this request
     * started the work. {@code PENDING} says the true thing — ask again shortly.
     */
    @GetMapping("/tickets/{ticketId}/qr")
    public QrResponse qr(@PathVariable UUID ticketId) {
        Ticket ticket = issuanceService.findTicket(ticketId);
        return qrStore.presign(ticket.getQrObjectKey())
                .map(presigned -> new QrResponse(QrStatus.READY, presigned.url(), presigned.expiresAt()))
                .orElseGet(() -> new QrResponse(QrStatus.PENDING, null, null));
    }

    public enum QrStatus { READY, PENDING }

    public record QrResponse(QrStatus status, String url, Instant expiresAt) { }

    public record TicketResponse(UUID id, UUID orderId, String eventId, String seatId,
                                 String customerRef, String gatewayRef, Instant issuedAt) {

        static TicketResponse of(Ticket ticket) {
            return new TicketResponse(ticket.getId(), ticket.getOrderId(), ticket.getEventId(),
                    ticket.getSeatId(), ticket.getCustomerRef(), ticket.getGatewayRef(),
                    ticket.getIssuedAt());
        }
    }
}
