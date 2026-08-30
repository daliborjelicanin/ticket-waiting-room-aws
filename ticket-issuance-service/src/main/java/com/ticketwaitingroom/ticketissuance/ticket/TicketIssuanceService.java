package com.ticketwaitingroom.ticketissuance.ticket;

import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import com.ticketwaitingroom.common.exceptions.TicketNotFoundException;
import com.ticketwaitingroom.ticketissuance.qr.QrGenerationDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Turns a message off {@code ticket-issuance-queue} into a ticket and a request to render
 * its QR code.
 *
 * <p>{@link #issue} is intentionally <em>not</em> transactional. It spans an SQS send, and
 * holding a database transaction across a network call is the same mistake
 * {@code CheckoutService} avoids around the payment gateway: it trades another service's
 * latency for this one's connection pool.
 *
 * <p>Which leaves the obvious gap — the insert commits, then the send fails, and a ticket
 * exists with no QR code. That is deliberate, and the redelivery is the repair. Nothing
 * deletes the message until this method returns, so the failed send means the same message
 * arrives again, the insert loses to the unique constraint, and the QR request is issued
 * from the {@code AlreadyIssued} branch. This is why the dispatch below sits outside the
 * switch rather than in the {@code Issued} arm: the duplicate path is not a no-op, it is
 * the recovery path.
 */
@Service
@RequiredArgsConstructor
public class TicketIssuanceService {

    private final TicketWriter ticketWriter;
    private final TicketRepository ticketRepository;
    private final QrGenerationDispatcher qrDispatcher;
    private final Clock clock;

    /**
     * Issue the ticket for one purchase. Idempotent on {@code (orderId, seatId)} — enforced
     * by {@code uq_tickets_order_seat}, not by a check in this method.
     */
    public IssueOutcome issue(TicketIssuanceMessage message) {
        UUID orderId = UUID.fromString(message.orderId());

        IssueOutcome outcome;
        try {
            outcome = new IssueOutcome.Issued(ticketWriter.insert(message, clock.instant()));
        } catch (DataIntegrityViolationException duplicate) {
            // A previous delivery — possibly one still in flight on another consumer —
            // already issued this ticket. The database decided; this reads back its answer.
            outcome = new IssueOutcome.AlreadyIssued(
                    ticketWriter.findIssued(orderId, message.seatId(), duplicate));
        }

        qrDispatcher.requestQr(outcome.ticket());
        return outcome;
    }

    @Transactional(readOnly = true)
    public List<Ticket> findByOrder(UUID orderId) {
        return ticketRepository.findByOrderIdOrderBySeatId(orderId);
    }

    @Transactional(readOnly = true)
    public Ticket findTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new TicketNotFoundException("No ticket " + ticketId));
    }
}
