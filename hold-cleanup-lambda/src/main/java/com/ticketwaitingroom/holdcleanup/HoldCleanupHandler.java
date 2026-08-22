package com.ticketwaitingroom.holdcleanup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.ticketwaitingroom.common.events.SeatHoldExpiredEvent;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Scheduled reclamation of expired seat holds, invoked by an EventBridge schedule.
 *
 * <p>The input is ignored — the schedule sends an empty payload, and the function's work
 * is defined entirely by what the table looks like at invocation time. That is deliberate:
 * it makes the function idempotent and safe to invoke at any cadence, from any source,
 * including twice concurrently. Every unit of work is a guarded conditional write in
 * {@link ExpiredHoldRepository#reclaim}, so a duplicate invocation reclaims nothing twice.
 *
 * <p>Failure policy is "do nothing clever". Each seat is reclaimed independently and a lost
 * race is counted, not retried — the next tick of the schedule is the retry, which is why
 * the EventBridge schedule is configured with zero retry attempts.
 */
public class HoldCleanupHandler implements RequestHandler<Map<String, Object>, HoldCleanupHandler.Summary> {

    private static final String TABLE_NAME_ENV = "SEAT_TABLE_NAME";

    private final ExpiredHoldRepository repository;
    private final Clock clock;

    /** Lambda's entry point: constructed once per execution environment. */
    public HoldCleanupHandler() {
        this(new ExpiredHoldRepository(SharedClient.INSTANCE, requiredEnv(TABLE_NAME_ENV)), Clock.systemUTC());
    }

    HoldCleanupHandler(ExpiredHoldRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Summary handleRequest(Map<String, Object> ignoredEvent, Context context) {
        List<SeatHoldExpiredEvent> expired = repository.findExpiredHolds(clock.instant());

        int reclaimed = 0;
        int lostRace = 0;
        for (SeatHoldExpiredEvent hold : expired) {
            // Re-read the clock per seat: a large batch can take long enough that a hold
            // near the boundary is no longer expired by the time its write is issued.
            if (repository.reclaim(hold, clock.instant())) {
                reclaimed++;
                log(context, "reclaimed seat=%s event=%s hold=%s expiredAt=%s"
                        .formatted(hold.seatId(), hold.eventId(), hold.holdId(), hold.expiredAt()));
            } else {
                lostRace++;
                log(context, "skipped seat=%s event=%s hold=%s — lost race to a concurrent write"
                        .formatted(hold.seatId(), hold.eventId(), hold.holdId()));
            }
        }

        Summary summary = new Summary(expired.size(), reclaimed, lostRace);
        log(context, "hold cleanup complete " + summary);
        return summary;
    }

    /**
     * @param expiredFound seats the scan saw as HELD past their expiry
     * @param reclaimed    seats actually returned to AVAILABLE by this invocation
     * @param lostRace     expired holds a concurrent writer resolved first — expected, not an error
     */
    public record Summary(int expiredFound, int reclaimed, int lostRace) { }

    /**
     * Holder idiom, not a plain static field. The client is built once per execution
     * environment and reused across invocations — so TLS setup and credential resolution
     * are paid on the cold start rather than every tick — but deferring it to first use
     * keeps the class loadable where the default region/credential chain resolves to
     * nothing, which is every test in this module.
     */
    private static final class SharedClient {
        private static final DynamoDbClient INSTANCE = DynamoDbClient.create();
    }

    /**
     * Fails loudly at construction rather than scanning a {@code null} table name and
     * surfacing it later as an opaque SDK error.
     */
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable " + name + " is not set");
        }
        return value;
    }

    /** {@code context} is null in unit tests, where stdout is still captured. */
    private static void log(Context context, String message) {
        if (context != null) {
            context.getLogger().log(message);
        } else {
            System.out.println(message);
        }
    }
}
