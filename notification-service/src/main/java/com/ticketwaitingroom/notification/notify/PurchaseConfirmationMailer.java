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
 * to it. This event fires when payment was captured, which is upstream of issuance and
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

    public void send(PurchaseConfirmedEvent event) {
        sender.send(render(event));
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
     * {@code customerRef} is an opaque customer reference, not an address — the saga's own
     * test input uses {@code cust-saga-1}. Treating it as an address when it happens to look
     * like one keeps a real end-to-end run honest, and everything else goes to the single
     * verified sandbox identity rather than failing the send.
     */
    private String recipientFor(PurchaseConfirmedEvent event) {
        String customerRef = event.customerRef();
        return customerRef != null && customerRef.contains("@") ? customerRef : properties.fallbackRecipient();
    }

    private static String formatAmount(long amountCents) {
        return String.format(Locale.ROOT, "%d.%02d", amountCents / 100, Math.abs(amountCents % 100));
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
