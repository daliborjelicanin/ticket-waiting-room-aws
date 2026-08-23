package com.ticketwaitingroom.waitingroom.queue;

/**
 * Where a token stands with the waiting room. Mirrors the status strings returned by the
 * Lua scripts in {@code src/main/resources/redis} — the names must stay in sync.
 */
public enum QueueStatus {

    /** In line, holding a ticket number. */
    WAITING,

    /** Admitted and free to shop until the admission pass expires. */
    ADMITTED,

    /** Was admitted, but the pass lapsed before checkout; the slot has been forfeited. */
    EXPIRED,

    /** Never joined, or dropped out of the queue entirely. */
    NOT_QUEUED
}
