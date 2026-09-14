package com.ticketwaitingroom.notification.notify;

import com.ticketwaitingroom.common.events.PurchaseConfirmedEvent;
import com.ticketwaitingroom.notification.config.NotificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Renders and sends the purchase-confirmation email.
 *
 * <p>The mail deliberately does <strong>not</strong> embed the QR image or a presigned link
 * to it. This event fires when payment was capturhttps://github.com/daliborjelicanined, which is upstream of issuance and
 * rendering — the ticket may not exist yet, and a presigned URL minted here would be for an
 * object that is not in the bucket and would expire long before anyone opened it anyway. So
 * the mail links to the issuance service, which is the component that actually knows
 * whether the QR is ready. That is also what real ticketing does: confirmation is immediate,
 * the ticket follows.
 */
@Service
@RequiredArgsConstructor
public class PurchaseConfirmationMailer {

    private final NotificationSender sender;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    public void send(PurchaseConfirmedEvent event) {
        Email email = render(event);
        try {
            sender.send(email);
        } catch (RuntimeException notSent) {
            metrics.sent(false);
            throw notSent;
        }
        metrics.sent(true);
    }

    Email render(PurchaseConfirmedEvent event) {
        String body = """
                Your purchase is confirmed.

                Event:    %s
                Seat:     %s
                Order:    %s
                Paid:     %s
                Payment:  %s

                Your ticket is being issued. It will be available shortly at:
                %s/orders/%s/tickets
                """.formatted(
                event.eventId(),
                event.seatId(),
                event.orderId(),
                formatAmount(event.amountCents()),
                event.gatewayRef(),
                trimTrailingSlash(properties.ticketBaseUrl()),
                event.orderId());

        return new Email(
                recipientFor(event),
                "Ticket purchase confirmed — seat %s".formatted(event.seatId()),
                body);
    }

    /**
     * The address comes from {@code email} — the verified Cognito claim, injected by API
     * Gateway rather than read from the request body, so a purchase cannot have its
     * confirmation redirected to whoever asked for it.
     *
     * <p>{@code customerRef} is deliberately not consulted: it now carries a Cognito
     * {@code sub}, which never looks like an address, so the older "use it if it contains
     * an @" rule would silently send every mail to the fallback and look like it worked.
     * The fallback remains for the SES sandbox, which only delivers to verified identities
     * anyway.
     */
    private String recipientFor(PurchaseConfirmedEvent event) {
        String email = event.email();
        return email != null && email.contains("@") ? email : properties.fallbackRecipient();
    }

    private static String formatAmount(long amountCents) {
        return String.format(Locale.ROOT, "%d.%02d", amountCents / 100, Math.abs(amountCents % 100));
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
