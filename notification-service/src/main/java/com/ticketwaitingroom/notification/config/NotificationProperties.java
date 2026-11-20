package com.ticketwaitingroom.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the notification service.
 *
 * @param queueUrl          the SQS queue subscribed to the {@code purchase-confirmed} topic
 * @param sender            {@code ses} or {@code logging}
 * @param fromAddress       the verified SES identity mail is sent from
 * @param fallbackRecipient where mail goes when the event carries no buyer email
 * @param sandboxRecipient  when set, where <em>every</em> mail goes: SES in the sandbox delivers
 *                          only to verified identities, and load-test buyers have made-up
 *                          addresses. Empty in production (SES production access, D20)
 * @param ticketBaseUrl     public base URL of ticket-issuance-service, linked from the mail
 * @param consumer          polling shape for the notification consumer
 */
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
        String queueUrl,
        String sender,
        String fromAddress,
        String fallbackRecipient,
        String sandboxRecipient,
        String ticketBaseUrl,
        ConsumerProperties consumer
) {

    public record ConsumerProperties(boolean enabled, int threads, int batchSize, Duration pollWait) { }
}
