package com.ticketwaitingroom.waitingroom.queue;

import java.time.Instant;

/**
 * A token's standing in the waiting room at one instant.
 *
 * <p>Position is deliberately a live read rather than a stored value: it is a token's
 * current rank in the sorted set, so it falls as the people ahead are admitted. The
 * ticket number behind it (the sorted-set score) is what is actually fixed at join time
 * and what admission order is decided by.
 *
 * @param position            1-based place in line, {@code null} unless {@link QueueStatus#WAITING}
 * @param queueLength         how many tokens are still waiting, this one included
 * @param admissionExpiresAt  when the admission pass lapses, {@code null} unless the token
 *                            has been admitted (whether the pass is still valid)
 */
public record QueuePlace(
        String eventId,
        String token,
        QueueStatus status,
        Long position,
        long queueLength,
        Instant admissionExpiresAt
) {
}
