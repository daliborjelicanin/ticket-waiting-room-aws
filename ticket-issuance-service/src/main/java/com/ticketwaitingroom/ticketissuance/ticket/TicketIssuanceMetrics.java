package com.ticketwaitingroom.ticketissuance.ticket;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * One counter, because one number matters here: {@code tickets.issued} ·
 * {@code outcome=issued|duplicate}. {@code issued} is a ticket row; {@code duplicate} is
 * a delivery that lost to {@code uq_tickets_order_seat}, which under at-least-once delivery
 * is ordinary and under a producer that has started retrying is the first thing to climb.
 * The ratio is the queue's redelivery rate, read from the only place that can see it.
 */
@Component
@RequiredArgsConstructor
public class TicketIssuanceMetrics {

    private final MeterRegistry registry;

    public void issued(IssueOutcome outcome) {
        String label = switch (outcome) {
            case IssueOutcome.Issued _ -> "issued";
            case IssueOutcome.AlreadyIssued _ -> "duplicate";
        };
        registry.counter("tickets.issued", "outcome", label).increment();
    }
}
