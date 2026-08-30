package com.ticketwaitingroom.ticketissuance.ticket;

import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The transactional half of issuing a ticket, split out so the surrounding orchestration —
 * which sends an SQS message — is not holding a database transaction while it does.
 *
 * <p>A separate bean rather than a private method for a duller reason: Spring's
 * {@code @Transactional} is a proxy, and a self-call inside one class goes straight past it.
 * A transaction that silently never starts is exactly the kind of failure this slice cannot
 * afford, since the constraint violation it relies on would then surface at an unrelated
 * commit boundary.
 */
@Service
@RequiredArgsConstructor
public class TicketWriter {

    private final TicketRepository ticketRepository;

    /**
     * Insert the ticket for this message.
     *
     * <p>Attempts the insert first rather than checking for an existing row: a
     * read-then-write leaves a window between the two statements, and two consumers racing
     * through that window both see "no ticket yet" and both insert. The constraint is
     * checked by the database at a point where no window exists.
     *
     * @throws DataIntegrityViolationException when a ticket for this order and seat already
     *                                         exists — the caller resolves it via
     *                                         {@link #findIssued}
     */
    @Transactional
    public Ticket insert(TicketIssuanceMessage message, Instant now) {
        // saveAndFlush, not save: the constraint has to be enforced here, inside the try
        // block that knows what a violation means, rather than at some later commit.
        return ticketRepository.saveAndFlush(Ticket.issue(message, now));
    }

    /**
     * Read the ticket a losing insert collided with.
     *
     * <p>{@code REQUIRES_NEW} because the transaction that hit the constraint is already
     * marked rollback-only and nothing further can be read on it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Ticket findIssued(UUID orderId, String seatId, DataIntegrityViolationException failure) {
        return ticketRepository.findByOrderIdAndSeatId(orderId, seatId)
                // Not the duplicate we expected — some other constraint failed, so the
                // original exception is the honest thing to propagate.
                .orElseThrow(() -> failure);
    }
}
