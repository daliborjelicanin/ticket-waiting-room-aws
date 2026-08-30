package com.ticketwaitingroom.ticketissuance.ticket;

import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import com.ticketwaitingroom.common.tickets.TicketQrObjects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A ticket for one seat on one paid order.
 *
 * <p>Immutable after creation. A ticket has no lifecycle to speak of — it is the record
 * that a purchase completed, and there is no legitimate transition that mutates it. Whether
 * its QR image exists yet is deliberately <em>not</em> state on this row: that is a fact
 * about an S3 object, owned by another deployable, and mirroring it here would be a second
 * copy of the truth that goes stale the moment the two writes are not atomic. The download
 * endpoint asks S3 instead.
 */
@Entity
@Table(name = "tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    private String seatId;

    @Column(name = "customer_ref", nullable = false, updatable = false)
    private String customerRef;

    @Column(name = "gateway_ref", updatable = false)
    private String gatewayRef;

    @Column(name = "qr_object_key", nullable = false, updatable = false)
    private String qrObjectKey;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    private Ticket(UUID id, UUID orderId, String eventId, String seatId, String customerRef,
                   String gatewayRef, Instant issuedAt) {
        this.id = id;
        this.orderId = orderId;
        this.eventId = eventId;
        this.seatId = seatId;
        this.customerRef = customerRef;
        this.gatewayRef = gatewayRef;
        this.qrObjectKey = TicketQrObjects.key(id);
        this.issuedAt = issuedAt;
    }

    /**
     * Mint a ticket from an issuance message.
     *
     * <p>The id is random rather than derived from {@code orderId + seatId}. A deterministic
     * id would let two concurrent deliveries agree on it, but they would then both insert
     * the same primary key and the loser would still have to read the winner's row — the
     * same code path, reached through a less obvious constraint. The unique constraint on
     * {@code (order_id, seat_id)} is the one that expresses the actual rule, so that is the
     * one this leans on.
     */
    public static Ticket issue(TicketIssuanceMessage message, Instant now) {
        return new Ticket(UUID.randomUUID(), UUID.fromString(message.orderId()), message.eventId(),
                message.seatId(), message.customerRef(), message.gatewayRef(), now);
    }
}
