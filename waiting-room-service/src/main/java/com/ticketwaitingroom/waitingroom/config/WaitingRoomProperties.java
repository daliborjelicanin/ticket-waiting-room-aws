package com.ticketwaitingroom.waitingroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the waiting-room service.
 *
 * @param admissionDuration how long an admitted token may shop before its pass lapses and
 *                          the slot is reclaimed for the next person in line
 * @param maxAdmitBatch     ceiling on a single admit call, so a typo cannot drain the
 *                          whole room into a checkout that cannot absorb it
 * @param keyTtl            idle lifetime of an event's queue keys; refreshed on every
 *                          join and admit, so it only fires once an event goes quiet
 * @param admissionQueueUrl where to announce admissions so the WebSocket notifier can push
 *                          them; blank means log them instead, which is the local default
 */
@ConfigurationProperties(prefix = "waiting-room")
public record WaitingRoomProperties(
        Duration admissionDuration,
        int maxAdmitBatch,
        Duration keyTtl,
        String admissionQueueUrl
) {
}
