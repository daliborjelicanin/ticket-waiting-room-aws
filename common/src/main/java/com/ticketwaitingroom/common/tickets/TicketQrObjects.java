package com.ticketwaitingroom.common.tickets;

import java.util.UUID;

/**
 * The S3 key contract for ticket QR codes, shared by the two deployables that disagree
 * about it at their peril: {@code ticket-issuance-service} records the key on the ticket
 * row and presigns it for download, while {@code qr-generation-lambda} is what actually
 * writes the object there.
 *
 * <p>The key is <em>derived</em> from the ticket id rather than chosen, and that is the
 * whole design. It means the QR job's message body can be nothing but the ticket id — no
 * JSON, no parser in the Lambda — and, more importantly, that generating the same ticket's
 * QR twice writes the same bytes to the same key. Idempotency by construction beats
 * idempotency by guard: the Lambda needs no conditional write and no dedupe table, because
 * a duplicate SQS delivery is indistinguishable from the first one.
 *
 * <p>Same reasoning as {@code SeatTableSchema}: a divergent key between the writer and the
 * reader is not a compile error and not a runtime error. It is a download endpoint that
 * reports PENDING forever while the object sits happily in the bucket under another name.
 */
public final class TicketQrObjects {

    private static final String PREFIX = "tickets/";
    private static final String SUFFIX = ".png";

    public static final String CONTENT_TYPE = "image/png";

    public static String key(UUID ticketId) {
        return PREFIX + ticketId + SUFFIX;
    }

    public static String key(String ticketId) {
        return PREFIX + ticketId + SUFFIX;
    }

    /**
     * What the QR image encodes: the URL a gate scanner would open to validate the ticket.
     * Encoding a URL rather than a bare id means a phone camera does something useful with
     * it, and it keeps the verification host out of the stored object's name.
     */
    public static String verificationUrl(String baseUrl, String ticketId) {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return root + "/tickets/" + ticketId;
    }

    private TicketQrObjects() {
    }
}
