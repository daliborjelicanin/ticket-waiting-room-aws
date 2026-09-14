package com.ticketwaitingroom.waitingroom.queue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the dashboard is told about the room. The queue is a mock: its ordering is proven
 * against real Redis in {@code WaitingRoomFairnessIT}; this is about the reporting of it.
 */
class WaitingRoomMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WaitingRoomQueue queue = mock(WaitingRoomQueue.class);
    private final WaitingRoomMetrics metrics = new WaitingRoomMetrics(registry, queue);

    @Test
    void joinsAreCountedByWhatTheCustomerWasTold() {
        metrics.joined(new QueuePlace("evt-1", "t-1", QueueStatus.WAITING, 1L, 1, null));
        metrics.joined(new QueuePlace("evt-1", "t-1", QueueStatus.WAITING, 1L, 1, null));
        metrics.joined(new QueuePlace("evt-1", "t-2", QueueStatus.ADMITTED, null, 0, null));

        assertThat(registry.get("waiting_room.joins").tag("status", "WAITING").counter().count()).isEqualTo(2);
        assertThat(registry.get("waiting_room.joins").tag("status", "ADMITTED").counter().count()).isEqualTo(1);
    }

    @Test
    void admissionsCountTokensAndFailedAnnouncementsCountSeparately() {
        metrics.admitted("evt-1", 3);
        metrics.admitted("evt-1", 0);
        metrics.admissionNotPublished();

        assertThat(registry.get("waiting_room.admissions").counter().count()).isEqualTo(3);
        assertThat(registry.get("waiting_room.admission.publish.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void depthsAreGaugedPerEventSeenAndSurviveAFailedRefresh() {
        metrics.joined(new QueuePlace("evt-1", "t-1", QueueStatus.WAITING, 1L, 1, null));
        when(queue.sizes("evt-1")).thenReturn(new WaitingRoomQueue.Sizes(42, 5));
        metrics.refreshDepths();
        assertThat(registry.get("waiting_room.queue.depth").tag("event", "evt-1").gauge().value()).isEqualTo(42);
        assertThat(registry.get("waiting_room.admitted").tag("event", "evt-1").gauge().value()).isEqualTo(5);

        when(queue.sizes("evt-1")).thenThrow(new IllegalStateException("redis down"));
        metrics.refreshDepths();

        assertThat(registry.get("waiting_room.queue.depth").tag("event", "evt-1").gauge().value()).isEqualTo(42);
    }
}
