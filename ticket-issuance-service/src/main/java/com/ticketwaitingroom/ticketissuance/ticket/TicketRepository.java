package com.ticketwaitingroom.ticketissuance.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByOrderIdAndSeatId(UUID orderId, String seatId);

    /**
     * The customer-facing listing. Scoped by {@code customerRef} in the query rather than
     * filtered afterwards, so a caller asking about somebody else's order gets an empty
     * list — indistinguishable from an order of their own whose tickets have not been
     * issued yet, which is exactly the answer that leaks nothing.
     */
    List<Ticket> findByOrderIdAndCustomerRefOrderBySeatId(UUID orderId, String customerRef);

    List<Ticket> findByOrderIdOrderBySeatId(UUID orderId);
}
