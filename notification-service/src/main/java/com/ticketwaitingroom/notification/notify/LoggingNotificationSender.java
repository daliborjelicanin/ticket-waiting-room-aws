package com.ticketwaitingroom.notification.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the email to the log instead of sending it. The default locally and in tests,
 * where SES is neither reachable nor desirable.
 */
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Email email) {
        log.info("[email] to={} subject={}\n{}", email.recipient(), email.subject(), email.body());
    }
}
