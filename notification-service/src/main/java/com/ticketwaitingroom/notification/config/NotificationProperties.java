package com.ticketwaitingroom.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the notification service.
 *
 * @param queueUrl          the SQS queue subscribed to the {@code purchase-confirmed} topic
 * @param sender            {@code ses} or {@code logging}
 * @param fromAddress       the verified SES identity mail is sent from
 * @param fallbackRecipient where mail goes when {@code customerRef} is not an address
 * @param ticketBaseUrl     public base URL of ticket-issuance-service, linked from the mail
 * @param consumer          polling shape for the notification consumer
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        String queueUrl,
        String sender,
        String fromAddress,
        String fallbackRecipient,
        String ticketBaseUrl,
        ConsumerProperties consumer
) {

    public record ConsumerProperties(boolean enabled, int threads, int batchSize, Duration pollWait) { }
}
