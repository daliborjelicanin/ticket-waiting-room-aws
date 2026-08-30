package com.ticketwaitingroom.ticketissuance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the ticket-issuance service.
 *
 * @param issuanceQueueUrl the queue the checkout saga enqueues purchases on
 * @param qrQueueUrl       the queue that feeds the QR-generation Lambda
 * @param qrBucket         S3 bucket holding the generated QR images
 * @param qrUrlTtl         how long a presigned download URL stays valid
 * @param consumer         polling shape for the issuance consumer
 */
@ConfigurationProperties(prefix = "ticket-issuance")
public record TicketIssuanceProperties(
        String issuanceQueueUrl,
        String qrQueueUrl,
        String qrBucket,
        Duration qrUrlTtl,
        ConsumerProperties consumer
) {

    /**
     * @param enabled   whether to poll at all — off for slice tests and any HTTP-only run
     * @param threads   concurrent receive loops; raising this is how throughput is raised,
     *                  and it is safe precisely because issuance is idempotent
     * @param batchSize messages per receive, 1-10
     * @param pollWait  long-poll duration, 0-20s
     */
    public record ConsumerProperties(boolean enabled, int threads, int batchSize, Duration pollWait) { }
}
