package com.ticketwaitingroom.notification.notify;

/**
 * Delivery of a rendered {@link Email}.
 *
 * <p>An interface for the same reason the payment gateway is one: the real implementation
 * talks to a third party that cannot be part of a test run, and the alternative is a bean
 * that provably exists rather than a mock assembled per test.
 *
 * <p>Implementations must throw on failure. The consumer's contract with SQS is that a
 * thrown exception means "do not delete this message", so a swallowed send failure is a
 * silently lost notification with no redelivery and no DLQ entry.
 */
public interface NotificationSender {

    void send(Email email);
}
