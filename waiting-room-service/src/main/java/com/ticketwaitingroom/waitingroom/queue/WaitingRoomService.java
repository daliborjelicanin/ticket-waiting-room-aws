package com.ticketwaitingroom.waitingroom.queue;

import com.ticketwaitingroom.common.exceptions.UnknownQueueTokenException;
import com.ticketwaitingroom.waitingroom.config.WaitingRoomProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Application service for the waiting room: join the line, check your place, admit the
 * next batch. Mints token identity and admission deadlines and supplies the current time;
 * the ordering and atomicity guarantees are entirely {@link WaitingRoomQueue}'s, and this
 * layer adds no locking of its own.
 */
@Service
@RequiredArgsConstructor
public class WaitingRoomService {

    private final WaitingRoomQueue queue;
    private final WaitingRoomProperties properties;
    private final Clock clock;

    /**
     * Join the queue for an event.
     *
     * @param token an existing token to re-join under, or {@code null}/blank to be issued
     *              a fresh one. Passing the token back makes the call idempotent, which
     *              is what keeps a retried join from costing the client its place.
     */
    public QueuePlace join(String eventId, String token) {
        String queueToken = StringUtils.hasText(token) ? token : UUID.randomUUID().toString();
        return queue.join(eventId, queueToken, clock.instant());
    }

    /**
     * Look up a token's standing.
     *
     * @throws UnknownQueueTokenException if the token is not in the queue and holds no
     *                                    admission pass
     */
    public QueuePlace place(String eventId, String token) {
        QueuePlace place = queue.place(eventId, token, clock.instant());
        if (place.status() == QueueStatus.NOT_QUEUED) {
            throw new UnknownQueueTokenException(
                    "Token " + token + " is not in the waiting room for event " + eventId);
        }
        return place;
    }

    /**
     * Let the next {@code count} tokens through. In the finished system this is driven by
     * how much capacity checkout has free; for now the caller names the number.
     *
     * @throws IllegalArgumentException if {@code count} is not between 1 and the
     *                                  configured maximum batch size
     */
    public Admission admitNext(String eventId, int count) {
        if (count < 1 || count > properties.maxAdmitBatch()) {
            throw new IllegalArgumentException(
                    "Admit count must be between 1 and " + properties.maxAdmitBatch() + ", was " + count);
        }
        Instant now = clock.instant();
        // Truncated because Redis stores the deadline as epoch seconds: without this the
        // expiry echoed back here and the one a later position lookup reports would differ.
        Instant admissionExpiresAt = now.plus(properties.admissionDuration()).truncatedTo(ChronoUnit.SECONDS);
        return new Admission(queue.admitNext(eventId, count, admissionExpiresAt, now), admissionExpiresAt);
    }

    public record Admission(List<String> tokens, Instant admissionExpiresAt) { }
}
