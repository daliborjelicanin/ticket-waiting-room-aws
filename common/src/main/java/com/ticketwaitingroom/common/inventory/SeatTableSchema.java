package com.ticketwaitingroom.common.inventory;

/**
 * The seat-inventory single-table contract, shared by every deployable that reads or
 * writes seat items.
 *
 * <p>These constants live in {@code common} rather than next to the repository that owns
 * the table because there is more than one writer: {@code seat-inventory-service} performs
 * the hold/release/confirm transitions, and {@code hold-cleanup-lambda} reclaims expired
 * holds. A divergent attribute name between the two is not a compile error and not a
 * runtime error — DynamoDB accepts any attribute name — it is a condition that silently
 * never matches. Naming the contract once removes that failure mode.
 *
 * <p>Item shape:
 * <pre>
 *   PK  = EVENT#&lt;eventId&gt;
 *   SK  = SEAT#&lt;seatId&gt;
 *   status        = AVAILABLE | HELD | SOLD
 *   holdId        = current hold id      (absent when AVAILABLE; kept on SOLD as the
 *                                         audit trail of the winning hold)
 *   holdExpiresAt = epoch-seconds TTL    (present only while HELD — removed on confirm
 *                                         so TTL can never delete a SOLD seat)
 * </pre>
 */
public final class SeatTableSchema {

    public static final String ATTR_PK = "pk";
    public static final String ATTR_SK = "sk";
    public static final String ATTR_EVENT_ID = "eventId";
    public static final String ATTR_SEAT_ID = "seatId";
    public static final String ATTR_STATUS = "status";
    public static final String ATTR_HOLD_ID = "holdId";

    /**
     * The DynamoDB TTL attribute, enabled on the table itself. Epoch <em>seconds</em> as a
     * Number — a String value, or milliseconds, is accepted on write and then silently
     * ignored by TTL forever.
     */
    public static final String ATTR_HOLD_EXPIRES_AT = "holdExpiresAt";

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_SOLD = "SOLD";

    public static String pk(String eventId) {
        return "EVENT#" + eventId;
    }

    public static String sk(String seatId) {
        return "SEAT#" + seatId;
    }

    private SeatTableSchema() {
    }
}