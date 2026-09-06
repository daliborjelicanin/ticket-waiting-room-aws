package com.ticketwaitingroom.waitingroom.queue;

import com.ticketwaitingroom.waitingroom.config.WaitingRoomProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admit path's side channel: whoever was let through has to be told, and the telling
 * must not be able to take the admission down with it.
 */
class AdmissionAnnouncementTest {

    private static final String EVENT_ID = "evt-1";
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");

    private WaitingRoomQueue queue;
    private AdmissionPublisher publisher;
    private WaitingRoomService service;

    @BeforeEach
    void setUp() {
        queue = mock(WaitingRoomQueue.class);
        publisher = mock(AdmissionPublisher.class);
        service = new WaitingRoomService(queue, publisher,
                new WaitingRoomProperties(Duration.ofMinutes(10), 500, Duration.ofHours(24), null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void announcesEveryAdmittedTokenWithTheDeadlineItWasGiven() {
        when(queue.admitNext(anyString(), anyInt(), any(), any())).thenReturn(List.of("t-1", "t-2"));

        WaitingRoomService.Admission admission = service.admitNext(EVENT_ID, 2);

        ArgumentCaptor<List<String>> tokens = ArgumentCaptor.captor();
        verify(publisher).publish(eq(EVENT_ID), tokens.capture(), eq(admission.admissionExpiresAt()));
        assertThat(tokens.getValue()).containsExactly("t-1", "t-2");
        assertThat(admission.admissionExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void anAdmissionThatCouldNotBeAnnouncedStillStands() {
        // ZPOPMIN does not roll back. Reporting failure here would make the caller retry
        // the admit and pop a second batch of people, so the exception must stop at the
        // service boundary — the client's poll is the fallback that makes this safe.
        when(queue.admitNext(anyString(), anyInt(), any(), any())).thenReturn(List.of("t-1"));
        doThrow(new IllegalStateException("SQS is down")).when(publisher).publish(any(), any(), any());

        assertThat(service.admitNext(EVENT_ID, 1).tokens()).containsExactly("t-1");
    }

    @Test
    void anEmptyQueueAnnouncesNothing() {
        when(queue.admitNext(anyString(), anyInt(), any(), any())).thenReturn(List.of());

        assertThat(service.admitNext(EVENT_ID, 5).tokens()).isEmpty();
        verify(publisher, never()).publish(any(), any(), any());
    }
}
