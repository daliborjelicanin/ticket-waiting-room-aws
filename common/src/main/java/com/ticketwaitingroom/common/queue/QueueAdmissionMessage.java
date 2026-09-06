package com.ticketwaitingroom.common.queue;

/**
 * The wire contract for one admitted token on {@code queue-admissions}, shared by
 * {@code waiting-room-service} (which sends it the instant {@code admit.lua} pops a token)
 * and {@code queue-socket-lambda} (which turns it into a WebSocket frame).
 *
 * <p><strong>The body is the bare token; everything else is a message attribute.</strong>
 * Same reasoning as {@link com.ticketwaitingroom.common.tickets.TicketQrObjects} — a JSON
 * body would put a parser on the Lambda's classpath, and slice 2 measured class loading
 * alone blowing Lambda's separate 10-second INIT budget. Message attributes are typed by
 * the SDK on both ends, so the two extra values cost no dependency at all.
 *
 * <p>Message shape:
 * <pre>
 *   body                = &lt;queue token&gt;        (a UUID string, minted by join)
 *   eventId             = String attribute      (which event's queue)
 *   admissionExpiresAt  = Number attribute      (epoch seconds; when the pass lapses)
 * </pre>
 *
 * <p>One message per token rather than one per admit call. A batch of 500 is 50
 * {@code SendMessageBatch} calls, and it lets the notifier fail, retry and DLQ a single
 * unreachable token without dragging its batch-mates along.
 */
public final class QueueAdmissionMessage {

    /** Which event's queue the token belongs to. String attribute. */
    public static final String ATTR_EVENT_ID = "eventId";

    /**
     * When the admission pass lapses, epoch <em>seconds</em> as a Number attribute. Sent so
     * the client can render a countdown from the frame alone, without a follow-up poll —
     * which is the entire point of pushing rather than letting it poll.
     */
    public static final String ATTR_ADMISSION_EXPIRES_AT = "admissionExpiresAt";

    /** Attribute data type for {@link #ATTR_EVENT_ID}, as SQS spells it. */
    public static final String TYPE_STRING = "String";

    /** Attribute data type for {@link #ATTR_ADMISSION_EXPIRES_AT}, as SQS spells it. */
    public static final String TYPE_NUMBER = "Number";

    private QueueAdmissionMessage() {
    }
}
