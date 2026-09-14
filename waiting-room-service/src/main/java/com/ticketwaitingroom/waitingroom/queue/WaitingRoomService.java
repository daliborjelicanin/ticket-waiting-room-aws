package com.ticketwaitingroom.waitingroom.queue;

import com.ticketwaitingroom.common.exceptions.UnknownQueueTokenException;
import com.ticketwaitingroom.waitingroom.config.WaitingRoomProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Application service for the waiting room: join the line, check your place, admit the
 * next batch. Mints candidate tokens and admission deadlines and supplies the current
 * time; the ordering, atomicity and one-place-per-person guarantees are entirely
 * {@link WaitingRoomQueue}'s, and this layer adds no locking of its own.
 */
@Service
@RequiredArgsConstructor
public class WaitingRoomService {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomService.class);

    private final WaitingRoomQueue queue;
    private final AdmissionPublisher admissionPublisher;
    private final WaitingRoomProperties properties;
    private final WaitingRoomMetrics metrics;
    private final Clock clock;

    /**
     * Join the queue for an event, or report the place this customer already holds.
     *
     * <p>The token minted here is only a <em>candidate</em>: the script discards it if
     * this customer already has one. Generating it unconditionally costs a UUID and keeps
     * the whole operation one round trip, where checking first would be a read, a decision
     * and a write with a race in the middle of them.
     *
     * @param customerRef the authenticated caller's {@code sub}; never blank, because the
     *                    security filter has already refused anonymous requests
     */
    public QueuePlace join(String eventId, String customerRef) {
        QueuePlace place = queue.join(eventId, customerRef, UUID.randomUUID().toString(), clock.instant());
        metrics.joined(place);
        return place;
    }

    /**
     * Look up a customer's standing.
     *
     * @throws UnknownQueueTokenException if this customer holds no place and no admission
     *                                    pass for the event
     */
    public QueuePlace place(String eventId, String customerRef) {
        QueuePlace place = queue.place(eventId, customerRef, clock.instant());
        if (place.status() == QueueStatus.NOT_QUEUED) {
            throw new UnknownQueueTokenException(
                    "No place in the waiting room for event " + eventId);
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
        metrics.admitted(eventId, tokens.size());

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
     * most a notification, and clients still hold {@code GET /events/{eventId}/queue} as
     * the source of truth.
     */
    private void announce(String eventId, List<String> tokens, Instant admissionExpiresAt) {
        if (tokens.isEmpty()) {
            return;
        }
        try {
            admissionPublisher.publish(eventId, tokens, admissionExpiresAt);
        } catch (RuntimeException notPublished) {
            metrics.admissionNotPublished();
            log.warn("Admitted {} tokens for event {} but could not publish the admission; "
                    + "those clients will only learn of it by polling", tokens.size(), eventId, notPublished);
        }
    }

    public record Admission(List<String> tokens, Instant admissionExpiresAt) { }
}
