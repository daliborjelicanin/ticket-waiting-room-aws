package com.ticketwaitingroom.waitingroom.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

/**
 * Redis-backed FIFO waiting room, one queue per event.
 *
 * <p>The fairness guarantee — first in, first admitted — rests on three things, and none
 * of them is the order requests happen to arrive at the API layer:
 *
 * <ul>
 *   <li>Every joiner is stamped with a ticket number from {@code INCR}, an atomic
 *       monotonic counter, and stored in a sorted set scored by it. Concurrent joins
 *       therefore get distinct, contiguous, totally-ordered numbers. Scoring by wall
 *       clock instead would tie whenever two joins landed in the same millisecond, and
 *       ties in a sorted set break by member name — which would silently order the queue
 *       by token UUID.</li>
 *   <li>Admission is {@code ZPOPMIN} from the head, so the earliest ticket number always
 *       leaves first, and the pop is atomic: two concurrent admitters get disjoint
 *       batches and no token can be admitted twice.</li>
 *   <li>A customer holds at most one place, claimed with {@code HSETNX} in the same
 *       script that enqueues it. Without this the first two guarantees are about
 *       <em>tokens</em> rather than about <em>people</em>: one actor can join ten thousand
 *       times, be ordered impeccably fairly, and still take the whole room.</li>
 * </ul>
 *
 * <p>Each operation is a single Lua script, so the multi-command sequences (stamp and
 * enqueue; pop and record the admission) cannot interleave with a competing caller or
 * half-apply. The scripts live in {@code src/main/resources/redis} and carry the
 * per-branch reasoning.
 *
 * <p>Keys per event, all sharing the {@code {eventId}} hashtag so a future move to a
 * clustered ElastiCache keeps them on one slot — a script spanning slots is rejected:
 * <pre>
 *   wr:{eventId}:queue     zset, token -&gt; ticket number   (still waiting)
 *   wr:{eventId}:seq       counter, the ticket-number source
 *   wr:{eventId}:admitted  zset, token -&gt; pass expiry     (let through)
 *   wr:{eventId}:identity  hash, customer -&gt; token        (one place per person)
 * </pre>
 */
@RequiredArgsConstructor
public class WaitingRoomQueue {

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> joinScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> positionScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> admitScript;
    private final long keyTtlSeconds;

    /**
     * Put a customer in line, or report where they already stand.
     *
     * <p>Idempotent by <em>identity</em>: a re-join keeps the original ticket number, so a
     * client retrying a join whose response was lost does not surrender its place to
     * everyone who arrived meanwhile — and, unlike the token-keyed version this replaces,
     * it does not need the client to have kept anything for that to hold. A customer whose
     * admission pass has lapsed re-joins from the back, under the same token.
     *
     * @param candidateToken the token to issue if this customer has never joined this
     *                       event; discarded if they already hold one
     */
    public QueuePlace join(String eventId, String customerRef, String candidateToken, Instant now) {
        List<?> reply = redis.execute(
                joinScript,
                List.of(queueKey(eventId), seqKey(eventId), admittedKey(eventId), identityKey(eventId)),
                customerRef, candidateToken, epochSeconds(now), Long.toString(keyTtlSeconds));
        return toPlace(eventId, reply);
    }

    /** Read a customer's current standing. Never mutates, so it is safe to poll hard. */
    public QueuePlace place(String eventId, String customerRef, Instant now) {
        List<?> reply = redis.execute(
                positionScript,
                List.of(queueKey(eventId), admittedKey(eventId), identityKey(eventId)),
                customerRef, epochSeconds(now));
        return toPlace(eventId, reply);
    }

    /**
     * Admit up to {@code count} tokens from the head of the queue, granting each a pass
     * valid until {@code admissionExpiresAt}. Lapsed passes are reclaimed first, so an
     * abandoned checkout returns its slot to the room rather than holding it forever.
     *
     * @return the admitted tokens in admission order; shorter than {@code count}, or
     *         empty, when the queue runs out
     */
    public List<String> admitNext(String eventId, int count, Instant admissionExpiresAt, Instant now) {
        List<?> reply = redis.execute(
                admitScript,
                List.of(queueKey(eventId), admittedKey(eventId), seqKey(eventId), identityKey(eventId)),
                Integer.toString(count), epochSeconds(admissionExpiresAt), epochSeconds(now),
                Long.toString(keyTtlSeconds));
        return reply.stream().map(String.class::cast).toList();
    }

    /**
     * The two sorted sets' sizes, for the gauges. {@code admitted} counts passes whether
     * they have lapsed — a lapsed pass stays in the set until an admit reclaims it or a
     * re-join removes it, so under no admissions the number is an upper bound. Two plain
     * reads, not a script: nothing here has to be atomic with anything.
     */
    public Sizes sizes(String eventId) {
        Long waiting = redis.opsForZSet().zCard(queueKey(eventId));
        Long admitted = redis.opsForZSet().zCard(admittedKey(eventId));
        return new Sizes(waiting != null ? waiting : 0, admitted != null ? admitted : 0);
    }

    public record Sizes(long waiting, long admitted) { }

    /**
     * Decodes the {status, position, queueLength, admissionExpiresAt, token} reply the join
     * and position scripts share. Both use {@code '0'} — and {@code ''} for the token — for
     * a field that does not apply, since a genuine nil would truncate the reply array on
     * the way out of Lua.
     */
    private static QueuePlace toPlace(String eventId, List<?> reply) {
        long position = Long.parseLong((String) reply.get(1));
        // Redis renders sorted-set scores as floating point, so parse wide and narrow.
        double admissionExpiresAt = Double.parseDouble((String) reply.get(3));
        String token = (String) reply.get(4);
        return new QueuePlace(
                eventId,
                token.isEmpty() ? null : token,
                QueueStatus.valueOf((String) reply.get(0)),
                position > 0 ? position : null,
                Long.parseLong((String) reply.get(2)),
                admissionExpiresAt > 0 ? Instant.ofEpochSecond((long) admissionExpiresAt) : null);
    }

    private static String epochSeconds(Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }

    private static String queueKey(String eventId) {
        return "wr:{" + eventId + "}:queue";
    }

    private static String seqKey(String eventId) {
        return "wr:{" + eventId + "}:seq";
    }

    private static String admittedKey(String eventId) {
        return "wr:{" + eventId + "}:admitted";
    }

    private static String identityKey(String eventId) {
        return "wr:{" + eventId + "}:identity";
    }
}
