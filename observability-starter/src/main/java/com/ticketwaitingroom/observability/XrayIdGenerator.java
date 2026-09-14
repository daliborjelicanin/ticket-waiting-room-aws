package com.ticketwaitingroom.observability;

import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.sdk.trace.IdGenerator;

import java.time.InstantSource;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Trace ids in the shape X-Ray requires: the first 32 bits are the epoch second the trace
 * started, the remaining 96 are random.
 *
 * <p>OpenTelemetry's default generator is 128 random bits, and X-Ray rejects those — the
 * collector's X-Ray exporter reads the leading four bytes as a timestamp and drops any
 * span whose "start" is more than thirty days off, which a random prefix nearly always is.
 * So every span from every service would be exported and none would ever appear. This is
 * the whole of {@code AwsXrayIdGenerator} from the OpenTelemetry contrib repository; it is
 * written here rather than depended on because that artifact also carries a remote
 * sampler, and with it okhttp, Jackson 2 and Caffeine into every service image.
 *
 * <p>Span ids are unchanged from the default: 64 random, non-zero bits.
 */
public final class XrayIdGenerator implements IdGenerator {

    private final InstantSource clock;

    public XrayIdGenerator() {
        this(InstantSource.system());
    }

    XrayIdGenerator(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public String generateTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long epochSeconds = clock.instant().getEpochSecond();
        long high = (epochSeconds << 32) | (random.nextInt() & 0xFFFF_FFFFL);
        long low = random.nextLong();
        return TraceId.fromLongs(high, low);
    }

    @Override
    public String generateSpanId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long id;
        do {
            id = random.nextLong();
        } while (id == 0);
        return SpanId.fromLong(id);
    }
}
