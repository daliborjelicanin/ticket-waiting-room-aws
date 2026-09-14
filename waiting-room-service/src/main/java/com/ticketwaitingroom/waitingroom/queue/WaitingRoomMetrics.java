package com.ticketwaitingroom.waitingroom.queue;

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
 * The waiting room as the dashboard sees it. Two counters, one failure counter, two gauges.
 *
 * <dl>
 *   <dt>{@code waiting_room.joins} · {@code status=WAITING|ADMITTED}</dt>
 *   <dd>Every join call, by what the customer was told. A re-join by someone already in
 *       line lands here too — it is a request, and the queue answered it — so this is
 *       traffic, not people; the gauges are people.</dd>
 *   <dt>{@code waiting_room.admissions}</dt>
 *   <dd>Tokens let through. With {@code seat.hold.attempts} on the inventory side this is
 *       the top of the conversion funnel the dashboard divides by.</dd>
 *   <dt>{@code waiting_room.admission.publish.failures}</dt>
 *   <dd>Admissions that stood but could not be announced. Non-zero means clients are
 *       back to polling for that batch; sustained non-zero means the queue or the
 *       notifier is down, and nothing else in this service would say so.</dd>
 *   <dt>{@code waiting_room.queue.depth} · {@code event=…}, {@code waiting_room.admitted} · {@code event=…}</dt>
 *   <dd>{@code ZCARD} of the two sorted sets, refreshed on a schedule for the events this
 *       task has seen. Depth is the number the whole slice exists to show falling under
 *       admission and rising under a door-open.</dd>
 * </dl>
 */
@Component
@RequiredArgsConstructor
public class WaitingRoomMetrics {

    private static final Logger log = LoggerFactory.getLogger(WaitingRoomMetrics.class);

    private final MeterRegistry registry;
    private final WaitingRoomQueue queue;

    private final Set<String> events = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> depth = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> admitted = new ConcurrentHashMap<>();

    public void joined(QueuePlace place) {
        events.add(place.eventId());
        registry.counter("waiting_room.joins", "status", place.status().name()).increment();
    }

    public void admitted(String eventId, int tokens) {
        events.add(eventId);
        registry.counter("waiting_room.admissions").increment(tokens);
    }

    public void admissionNotPublished() {
        registry.counter("waiting_room.admission.publish.failures").increment();
    }

    @Scheduled(fixedDelayString = "${waiting-room.queue-depth-refresh}")
    void refreshDepths() {
        for (String eventId : events) {
            try {
                WaitingRoomQueue.Sizes sizes = queue.sizes(eventId);
                gauge(depth, "waiting_room.queue.depth", eventId, sizes.waiting());
                gauge(admitted, "waiting_room.admitted", eventId, sizes.admitted());
            } catch (RuntimeException failed) {
                log.warn("could not read queue sizes for event {}; keeping the last reading", eventId, failed);
            }
        }
    }

    private void gauge(Map<String, AtomicLong> gauges, String name, String eventId, long value) {
        gauges.computeIfAbsent(eventId, id -> registry.gauge(name, Tags.of("event", id), new AtomicLong(value)))
                .set(value);
    }
}
