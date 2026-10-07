package com.ticketwaitingroom.seatinventory.inventory;

import com.ticketwaitingroom.common.exceptions.HoldNotFoundException;
import com.ticketwaitingroom.common.exceptions.SeatUnavailableException;
import com.ticketwaitingroom.seatinventory.config.SeatInventoryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the dashboard will be told, driven through {@link SeatHoldService} so the count
 * happens where the outcome is decided. The repository is a mock: which conditional write
 * wins is proven against real DynamoDB in {@code SeatHoldConcurrencyIT}; this is about
 * whether a loss is <em>reported</em> as one.
 */
class SeatInventoryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeatRepository repository = mock(SeatRepository.class);
    private final SeatInventoryMetrics metrics = new SeatInventoryMetrics(registry, repository);
    private final SeatHoldService service = new SeatHoldService(
            repository,
            new SeatInventoryProperties("seat-inventory", Duration.ofMinutes(5)),
            metrics,
            Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void aWonAndALostHoldAreCountedApart() {
        service.holdSeat("evt-1", "A-1");
        doThrow(new SeatUnavailableException("taken")).when(repository).holdSeat(eq("evt-1"), eq("A-2"), anyString(), any());
        assertThatThrownBy(() -> service.holdSeat("evt-1", "A-2")).isInstanceOf(SeatUnavailableException.class);

        assertThat(registry.get("seat.hold.attempts").tag("outcome", "held").counter().count()).isEqualTo(1);
        assertThat(registry.get("seat.hold.attempts").tag("outcome", "unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void releasesAndConfirmationsReportStaleHolds() {
        service.releaseHold("evt-1", "A-1", "h-1");
        doThrow(new HoldNotFoundException("gone")).when(repository).releaseHold("evt-1", "A-1", "h-stale");
        assertThatThrownBy(() -> service.releaseHold("evt-1", "A-1", "h-stale")).isInstanceOf(HoldNotFoundException.class);

        when(repository.confirmPurchase(eq("evt-1"), eq("A-3"), eq("h-3"), any())).thenReturn(true, false);
        service.confirmPurchase("evt-1", "A-3", "h-3");
        service.confirmPurchase("evt-1", "A-3", "h-3");
        doThrow(new HoldNotFoundException("expired")).when(repository).confirmPurchase(eq("evt-1"), eq("A-4"), eq("h-4"), any());
        assertThatThrownBy(() -> service.confirmPurchase("evt-1", "A-4", "h-4")).isInstanceOf(HoldNotFoundException.class);

        assertThat(registry.get("seat.hold.releases").tag("outcome", "released").counter().count()).isEqualTo(1);
        assertThat(registry.get("seat.hold.releases").tag("outcome", "not_found").counter().count()).isEqualTo(1);
        assertThat(registry.get("seat.purchase.confirmations").tag("outcome", "confirmed").counter().count()).isEqualTo(1);
        assertThat(registry.get("seat.purchase.confirmations").tag("outcome", "replayed").counter().count()).isEqualTo(1);
        assertThat(registry.get("seat.purchase.confirmations").tag("outcome", "not_found").counter().count()).isEqualTo(1);
    }

    @Test
    void remainingSeatsAreGaugedPerEventSeenAndOnlyOnceCounted() {
        assertThat(registry.find("seats.remaining").gauge()).isNull();
        metrics.seatsSeeded("evt-1");
        service.holdSeat("evt-2", "B-1");
        when(repository.countAvailable("evt-1")).thenReturn(120L);
        when(repository.countAvailable("evt-2")).thenReturn(7L);

        metrics.refreshRemaining();

        assertThat(registry.get("seats.remaining").tag("event", "evt-1").gauge().value()).isEqualTo(120);
        assertThat(registry.get("seats.remaining").tag("event", "evt-2").gauge().value()).isEqualTo(7);
    }

    @Test
    void aFailedRefreshKeepsTheLastReading() {
        metrics.seatsSeeded("evt-1");
        when(repository.countAvailable("evt-1")).thenReturn(50L);
        metrics.refreshRemaining();
        when(repository.countAvailable("evt-1")).thenThrow(new IllegalStateException("dynamo down"));

        metrics.refreshRemaining();

        assertThat(registry.get("seats.remaining").tag("event", "evt-1").gauge().value()).isEqualTo(50);
    }
}
