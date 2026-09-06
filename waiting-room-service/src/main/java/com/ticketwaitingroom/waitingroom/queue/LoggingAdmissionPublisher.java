package com.ticketwaitingroom.waitingroom.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

/**
 * Writes the admission to the log instead of publishing it. The default locally and in
 * tests, where SQS is neither reachable nor desirable — and the reason the service still
 * starts with no queue configured.
 */
public class LoggingAdmissionPublisher implements AdmissionPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdmissionPublisher.class);

    @Override
    public void publish(String eventId, List<String> tokens, Instant admissionExpiresAt) {
        log.info("[admitted] event={} tokens={} expiresAt={}", eventId, tokens, admissionExpiresAt);
    }
}
