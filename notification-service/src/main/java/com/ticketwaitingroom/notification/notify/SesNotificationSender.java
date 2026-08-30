package com.ticketwaitingroom.notification.notify;

import com.ticketwaitingroom.notification.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Sends through SES v2.
 *
 * <p>In the sandbox both the sender <em>and</em> every recipient must be a verified
 * identity, so an unverified address fails the call rather than bouncing later — which is
 * the better failure, since a thrown exception leaves the message on the queue.
 */
@RequiredArgsConstructor
public class SesNotificationSender implements NotificationSender {

    private final SesV2Client ses;
    private final NotificationProperties properties;

    @Override
    public void send(Email email) {
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(properties.fromAddress())
                .destination(destination -> destination.toAddresses(email.recipient()))
                .content(content -> content.simple(message -> message
                        .subject(subject -> subject.data(email.subject()))
                        .body(body -> body.text(text -> text.data(email.body())))))
                .build());
    }
}
