package com.ticketwaitingroom.waitingroom.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

/**
 * Redis-backed FIFO waiting room, one queue per event.
 *
 * <p>The fairness guarantee — first in, first admitted — rests on two things, and neither
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
     * Put a token in line, or report where it already stands. Idempotent by token: a
     * re-join keeps the original ticket number, so a client retrying a join whose
     * response was lost does not surrender its place to everyone who arrived meanwhile.
     * A token whose admission pass has lapsed re-joins from the back.
     */
    public QueuePlace join(String eventId, String token, Instant now) {
        List<?> reply = redis.execute(
                joinScript,
                List.of(queueKey(eventId), seqKey(eventId), admittedKey(eventId)),
                token, epochSeconds(now), Long.toString(keyTtlSeconds));
        return toPlace(eventId, token, reply);
    }

    /** Read a token's current standing. Never mutates, so it is safe to poll hard. */
    public QueuePlace place(String eventId, String token, Instant now) {
        List<?> reply = redis.execute(
                positionScript,
                List.of(queueKey(eventId), admittedKey(eventId)),
                token, epochSeconds(now));
        return toPlace(eventId, token, reply);
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
                List.of(queueKey(eventId), admittedKey(eventId), seqKey(eventId)),
                Integer.toString(count), epochSeconds(admissionExpiresAt), epochSeconds(now),
                Long.toString(keyTtlSeconds));
        return reply.stream().map(String.class::cast).toList();
    }

    /**
     * Decodes the {status, position, queueLength, admissionExpiresAt} reply the join and
     * position scripts share. Both use {@code '0'} for a field that does not apply, since
     * a genuine nil would truncate the reply array on the way out of Lua.
     */
    private static QueuePlace toPlace(String eventId, String token, List<?> reply) {
        long position = Long.parseLong((String) reply.get(1));
        // Redis renders sorted-set scores as floating point, so parse wide and narrow.
        double admissionExpiresAt = Double.parseDouble((String) reply.get(3));
        return new QueuePlace(
                eventId,
                token,
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
}
