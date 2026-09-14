package com.ticketwaitingroom.notification.notify;

import com.ticketwaitingroom.common.events.PurchaseConfirmedEvent;
import com.ticketwaitingroom.notification.config.NotificationProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PurchaseConfirmationMailerTest {

    private static final PurchaseConfirmedEvent EVENT = new PurchaseConfirmedEvent(
            "6f1c0e0a-0000-4000-8000-000000000001", "evt-1", "A-12",
            "b4c1e0a2-1111-4000-8000-000000000009", "buyer@example.test", "gw-ref-9", 12000L);

    private final NotificationSender sender = mock(NotificationSender.class);

    @Test
    void rendersTheConfirmationWithOrderSeatAndAmount() {
        Email email = mailer("no-reply@example.test", "me@example.test").render(EVENT);

        assertThat(email.subject()).isEqualTo("Ticket purchase confirmed — seat A-12");
        assertThat(email.body())
                .contains("evt-1")
                .contains("A-12")
                .contains(EVENT.orderId())
                .contains("gw-ref-9")
                // Minor units formatted as major ones. 12000 cents is 120.00, not 12000.
                .contains("120.00");
    }

    @Test
    void linksToTheIssuanceServiceRatherThanEmbeddingAQr() {
        Email email = mailer("no-reply@example.test", "me@example.test").render(EVENT);

        // This event fires at capture, which is upstream of issuance and rendering — a
        // presigned URL minted here would point at an object that does not exist yet.
        assertThat(email.body()).contains("https://tickets.example.test/orders/" + EVENT.orderId() + "/tickets");
        assertThat(email.body()).doesNotContain("X-Amz-Signature");
    }

    @Test
    void sendsToTheVerifiedAddressFromTheEvent() {
        Email email = mailer("no-reply@example.test", "verified@example.test").render(EVENT);

        assertThat(email.recipient()).isEqualTo("buyer@example.test");
    }

    @Test
    void neverTreatsTheCustomerReferenceAsAnAddress() {
        // The reference is a Cognito sub. Even if one were shaped like an address, it is an
        // identifier the caller could once influence -- the address must come from the
        // verified claim or not at all.
        PurchaseConfirmedEvent noEmail = new PurchaseConfirmedEvent(
                EVENT.orderId(), "evt-1", "A-12", "attacker@example.test", null, "gw-ref-9", 12000L);

        Email email = mailer("no-reply@example.test", "verified@example.test").render(noEmail);

        assertThat(email.recipient()).isEqualTo("verified@example.test");
    }

    private PurchaseConfirmationMailer mailer(String from, String fallback) {
        return new PurchaseConfirmationMailer(sender, new NotificationProperties(
                "https://sqs.test/queue", "logging", from, fallback, "https://tickets.example.test/",
                new NotificationProperties.ConsumerProperties(false, 1, 10, Duration.ofSeconds(1))),
                new NotificationMetrics(new SimpleMeterRegistry()));
    }
}
