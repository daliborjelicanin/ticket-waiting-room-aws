package com.ticketwaitingroom.waitingroom.queue;

import java.time.Instant;
import java.util.List;

/**
 * Announces that tokens have been let through, so something outside this service can tell
 * the people holding them.
 *
 * <p>An interface for the same reason {@code NotificationSender} is one: the real
 * implementation talks to AWS, and the alternative to a seam here is a service that cannot
 * be run or tested without it.
 *
 * <p>Implementations must not be relied upon to succeed. The caller treats a failure as a
 * missed notification, never as a failed admission — see
 * {@link WaitingRoomService#admitNext} for why that asymmetry is the only safe one.
 */
public interface AdmissionPublisher {

    void publish(String eventId, List<String> tokens, Instant admissionExpiresAt);
}
