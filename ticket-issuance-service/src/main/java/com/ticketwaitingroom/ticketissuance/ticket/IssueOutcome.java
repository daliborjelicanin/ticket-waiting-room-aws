package com.ticketwaitingroom.ticketissuance.ticket;

/**
 * What happened when a delivery of an issuance message was processed.
 *
 * <p>Sealed, and both cases carry the same ticket, because the distinction is not about
 * what the caller does next — it does the same thing either way — but about what gets
 * counted and logged. A production run where {@code AlreadyIssued} is common means SQS is
 * redelivering, and that is worth being able to see rather than infer.
 */
public sealed interface IssueOutcome {

    Ticket ticket();

    /** This delivery created the ticket. */
    record Issued(Ticket ticket) implements IssueOutcome { }

    /** A previous delivery already created it; this one is a duplicate and is a no-op. */
    record AlreadyIssued(Ticket ticket) implements IssueOutcome { }
}
