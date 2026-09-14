package com.ticketwaitingroom.observability;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class XrayIdGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-09-13T10:15:30Z");

    private final XrayIdGenerator generator = new XrayIdGenerator(InstantSource.fixed(NOW));

    @Test
    void traceIdLeadsWithTheEpochSecondXrayReads() {
        String traceId = generator.generateTraceId();

        assertThat(TraceId.isValid(traceId)).isTrue();
        // The first eight hex digits are the timestamp; the X-Ray exporter drops a trace
        // whose leading bytes do not decode to a recent time.
        assertThat(traceId).hasSize(32).startsWith(Long.toHexString(NOW.getEpochSecond()));
    }

    @Test
    void traceIdsAreDistinctBeyondTheTimestamp() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(generator.generateTraceId());
        }
        assertThat(seen).hasSize(1_000);
    }

    @Test
    void spanIdsAreValid() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(SpanId.isValid(generator.generateSpanId())).isTrue();
        }
    }
}
