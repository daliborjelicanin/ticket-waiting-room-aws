package com.ticketwaitingroom.ticketissuance.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByOrderIdAndSeatId(UUID orderId, String seatId);

    List<Ticket> findByOrderIdOrderBySeatId(UUID orderId);
}
