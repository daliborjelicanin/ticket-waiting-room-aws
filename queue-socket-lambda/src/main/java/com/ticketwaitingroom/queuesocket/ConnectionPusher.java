package com.ticketwaitingroom.queuesocket;

/**
 * Sends one frame down one open WebSocket.
 *
 * <p>An interface for the same reason {@code NotificationSender} and {@code PaymentGateway}
 * are: the real implementation talks to a service that cannot be part of a test run.
 * LocalStack does not implement the {@code @connections} management API, so without this
 * seam the notifier's interesting behaviour — fan-out to several tabs, and pruning a
 * socket that has since closed — would be untestable.
 *
 * <p>Implementations must let {@code GoneException} escape rather than swallowing it: a
 * 410 is the only signal that a registry row is stale, and it is the notifier's job to act
 * on it.
 */
interface ConnectionPusher {

    void push(String connectionId, String payload);
}
