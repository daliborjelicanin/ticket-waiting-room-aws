package com.ticketwaitingroom.common.exceptions;

/**
 * The record exists, but it belongs to somebody else.
 *
 * <p><strong>Mapped to 404, never 403.</strong> A 403 confirms the id is real, which turns
 * any of these endpoints into an oracle for enumerating other people's orders and tickets —
 * and the ids are the only thing standing between an attacker and knowing who bought what.
 * Both services already answer 404 for a hold that was never yours, so this is the existing
 * convention rather than a new one.
 *
 * <p>Named for what it means rather than for the HTTP status, so the mapping stays a
 * decision the exception handler makes and not one baked into the domain.
 */
public class NotYoursException extends RuntimeException {

    public NotYoursException(String message) {
        super(message);
    }
}
