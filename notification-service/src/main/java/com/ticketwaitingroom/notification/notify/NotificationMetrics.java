package com.ticketwaitingroom.notification.notify;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code notifications.sent} · {@code outcome=sent|failed}. A failure here is an SES
 * refusal or outage; the message goes back on the queue, and the retry is a second
 * increment of one or the other. Sustained {@code failed} with the DLQ filling is the
 * only signal this service gives that mail has stopped, so it is the one worth a counter.
 */
@Component
@RequiredArgsConstructor
public class NotificationMetrics {

    private final MeterRegistry registry;

    public void sent(boolean succeeded) {
        registry.counter("notifications.sent", "outcome", succeeded ? "sent" : "failed").increment();
    }
}
