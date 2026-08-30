package com.ticketwaitingroom.notification.notify;

/**
 * A rendered message, ready to send.
 *
 * <p>Rendering is separated from sending so the two can be tested apart: what the customer
 * reads is asserted without an SES client anywhere near it, and delivery is asserted
 * without caring what the words are.
 */
public record Email(
        String recipient,
        String subject,
        String body
) {
}
