package com.ticketwaitingroom.seatinventory.inventory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The numbers the dashboard reads off this service. Four counters and one gauge, chosen
 * because each answers a question the load test will ask, and nothing else — every series
 * exported is a billable CloudWatch metric, and Boot's default JVM and Tomcat meters are
 * switched off for the same reason.
 *
 * <dl>
 *   <dt>{@code seat.hold.attempts} · {@code outcome=held|unavailable}</dt>
 *   <dd>Contention, directly: {@code unavailable} is the conditional write losing. Under
 *       the load test the ratio of the two is the headline — N racers, one winner.</dd>
 *   <dt>{@code seat.hold.releases} · {@code outcome=released|not_found}</dt>
 *   <dd>Compensations. {@code not_found} is a release arriving after the hold expired or
 *       was re-acquired, which the guard in {@link SeatRepository#releaseHold} refuses.</dd>
 *   <dt>{@code seat.purchase.confirmations} · {@code outcome=confirmed|not_found}</dt>
 *   <dd>Sales, and the confirm-after-expiry refusals the TTL check produces.</dd>
 *   <dt>{@code seats.remaining} · {@code event=…}</dt>
 *   <dd>Available seats per event, refreshed on a schedule from a count query — not on
 *       the request path, where a query per hold would cost more than the hold. Only
 *       events this task has seen since it started; there is no event catalog to walk.</dd>
 * </dl>
 */
@Component
@RequiredArgsConstructor
public class SeatInventoryMetrics {

    private static final Logger log = LoggerFactory.getLogger(SeatInventoryMetrics.class);

    private final MeterRegistry registry;
    private final SeatRepository seatRepository;

    private final Set<String> events = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> remaining = new ConcurrentHashMap<>();

    public void holdAttempted(String eventId, boolean held) {
        events.add(eventId);
        counter("seat.hold.attempts", held ? "held" : "unavailable").increment();
    }

    public void holdReleased(String eventId, boolean found) {
        events.add(eventId);
        counter("seat.hold.releases", found ? "released" : "not_found").increment();
    }

    public void purchaseConfirmed(String eventId, boolean found) {
        events.add(eventId);
        counter("seat.purchase.confirmations", found ? "confirmed" : "not_found").increment();
    }

    public void seatsSeeded(String eventId) {
        events.add(eventId);
    }

    /**
     * One count query per known event. A gauge is registered on the first successful
     * count rather than up front, so an event never reports a made-up zero while its
     * first query is still in flight; a failed refresh keeps the previous reading.
     */
    @Scheduled(fixedDelayString = "${seat-inventory.remaining-seats-refresh}")
    void refreshRemaining() {
        for (String eventId : events) {
            try {
                long available = seatRepository.countAvailable(eventId);
                remaining.computeIfAbsent(eventId, id ->
                        registry.gauge("seats.remaining", Tags.of("event", id), new AtomicLong(available)))
                        .set(available);
            } catch (RuntimeException failed) {
                log.warn("could not count remaining seats for event {}; keeping the last reading", eventId, failed);
            }
        }
    }

    private Counter counter(String name, String outcome) {
        return registry.counter(name, "outcome", outcome);
    }
}
