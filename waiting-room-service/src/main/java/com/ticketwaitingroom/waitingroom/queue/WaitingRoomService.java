package com.ticketwaitingroom.waitingroom.queue;

import com.ticketwaitingroom.common.exceptions.UnknownQueueTokenException;
import com.ticketwaitingroom.waitingroom.config.WaitingRoomProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);

    private final WaitingRoomQueue queue;
    private final AdmissionPublisher admissionPublisher;
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
        List<String> tokens = queue.admitNext(eventId, count, admissionExpiresAt, now);

        announce(eventId, tokens, admissionExpiresAt);
        return new Admission(tokens, admissionExpiresAt);
    }

    /**
     * Tell the admitted tokens they are through, on a best-effort basis.
     *
     * <p><strong>A failure here must not fail the admission.</strong> By the time this runs
     * the tokens are already out of the queue — {@code ZPOPMIN} does not roll back — so
     * throwing would report failure for work that succeeded, and the caller's natural retry
     * would pop a second batch of people who were never told either. Swallowing costs at
     * most a notification, and clients still hold
     * {@code GET /events/{eventId}/queue/{token}} as the source of truth.
     */
    private void announce(String eventId, List<String> tokens, Instant admissionExpiresAt) {
        if (tokens.isEmpty()) {
            return;
        }
        try {
            admissionPublisher.publish(eventId, tokens, admissionExpiresAt);
        } catch (RuntimeException notPublished) {
            log.warn("Admitted {} tokens for event {} but could not publish the admission; "
                    + "those clients will only learn of it by polling", tokens.size(), eventId, notPublished);
        }
    }

    public record Admission(List<String> tokens, Instant admissionExpiresAt) { }
}
