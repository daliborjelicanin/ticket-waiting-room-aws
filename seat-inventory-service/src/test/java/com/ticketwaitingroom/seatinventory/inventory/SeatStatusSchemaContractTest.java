package com.ticketwaitingroom.seatinventory.inventory;

import com.ticketwaitingroom.common.inventory.SeatTableSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins this service's {@link SeatStatus} enum to the wire values in
 * {@link SeatTableSchema}, which {@code hold-cleanup-lambda} writes its conditions
 * against.
 *
 * <p>Renaming an enum constant here is a refactor the compiler happily accepts and the
 * Lambda never hears about; its {@code #status = :held} condition would simply stop
 * matching and expired holds would quietly stop being reclaimed. This test is the
 * tripwire for that.
 */
class SeatStatusSchemaContractTest {

    @Test
    void enumNamesMatchTheSharedTableSchema() {
        assertThat(SeatStatus.AVAILABLE.name()).isEqualTo(SeatTableSchema.STATUS_AVAILABLE);
        assertThat(SeatStatus.HELD.name()).isEqualTo(SeatTableSchema.STATUS_HELD);
        assertThat(SeatStatus.SOLD.name()).isEqualTo(SeatTableSchema.STATUS_SOLD);
    }

    @Test
    void everyStatusIsCoveredBySchemaConstants() {
        assertThat(SeatStatus.values())
                .as("a new lifecycle state needs a matching SeatTableSchema constant "
                        + "before the cleanup Lambda can reason about it")
                .hasSize(3);
    }
}
