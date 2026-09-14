package com.ticketwaitingroom.seatinventory.inventory;

import com.ticketwaitingroom.common.exceptions.HoldNotFoundException;
import com.ticketwaitingroom.common.exceptions.SeatUnavailableException;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Map;

import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_EVENT_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_HOLD_EXPIRES_AT;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_HOLD_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_PK;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_SEAT_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_SK;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_STATUS;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.pk;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.sk;

/**
 * Single-table DynamoDB access for seat inventory.
 *
 * <p>The whole point of this class is that every state transition ({@link #holdSeat},
 * {@link #releaseHold}, {@link #confirmPurchase}) is an atomic, race-free operation
 * performed by a single {@code UpdateItem} guarded by a {@code ConditionExpression}.
 * DynamoDB serializes conditional writes per item, so exactly one of many concurrent
 * attempts for the same seat can win — with no application-level lock and no
 * read-modify-write window.
 *
 * <p>Item shape (single-table design, room to grow for holds/orders later):
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
@RequiredArgsConstructor
public class SeatRepository {

    private final DynamoDbClient dynamo;
    private final String tableName;

    /**
     * Seed a seat in the {@link SeatStatus#AVAILABLE} state. The conditional put refuses
     * to overwrite an existing item, so seeding can never silently clobber a held/sold
     * seat — an already-existing seat is reported as {@code false}, not an error, which
     * makes seeding safely re-runnable.
     *
     * @return {@code true} if the seat was created, {@code false} if it already existed
     */
    public boolean createAvailableSeat(String eventId, String seatId) {
        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            ATTR_PK, s(pk(eventId)),
                            ATTR_SK, s(sk(seatId)),
                            ATTR_EVENT_ID, s(eventId),
                            ATTR_SEAT_ID, s(seatId),
                            ATTR_STATUS, s(SeatStatus.AVAILABLE.name())))
                    .conditionExpression("attribute_not_exists(" + ATTR_PK + ")")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /**
     * Atomically transition a seat from {@link SeatStatus#AVAILABLE} to
     * {@link SeatStatus#HELD}. Race-free: the {@code ConditionExpression} ensures to
     * write only applies if the seat is currently AVAILABLE, so concurrent callers for
     * the same seat cannot both succeed.
     *
     * @throws SeatUnavailableException if the seat is not AVAILABLE (already held, sold,
     *                                  or nonexistent) — i.e. this caller lost the race.
     */
    public void holdSeat(String eventId, String seatId, String holdId, Instant holdExpiresAt) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ATTR_PK, s(pk(eventId)),
                            ATTR_SK, s(sk(seatId))))
                    .updateExpression("SET #status = :held, #holdId = :holdId, #ttl = :ttl")
                    .conditionExpression("#status = :available")
                    .expressionAttributeNames(Map.of(
                            "#status", ATTR_STATUS,
                            "#holdId", ATTR_HOLD_ID,
                            "#ttl", ATTR_HOLD_EXPIRES_AT))
                    .expressionAttributeValues(Map.of(
                            ":held", s(SeatStatus.HELD.name()),
                            ":available", s(SeatStatus.AVAILABLE.name()),
                            ":holdId", s(holdId),
                            ":ttl", n(holdExpiresAt.getEpochSecond())))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new SeatUnavailableException(
                    "Seat " + seatId + " of event " + eventId + " is not available to hold");
        }
    }

    /**
     * Atomically release a hold, transitioning the seat from {@link SeatStatus#HELD}
     * back to {@link SeatStatus#AVAILABLE}. Guarded on the {@code holdId} so a stale
     * caller (whose hold already expired and was re-acquired by someone else) can never
     * release the current owner's hold. This is also the compensation action the
     * checkout saga invokes on payment failure.
     *
     * @throws HoldNotFoundException if the seat is not HELD under this {@code holdId}
     */
    public void releaseHold(String eventId, String seatId, String holdId) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ATTR_PK, s(pk(eventId)),
                            ATTR_SK, s(sk(seatId))))
                    .updateExpression("SET #status = :available REMOVE #holdId, #ttl")
                    .conditionExpression("#status = :held AND #holdId = :holdId")
                    .expressionAttributeNames(Map.of(
                            "#status", ATTR_STATUS,
                            "#holdId", ATTR_HOLD_ID,
                            "#ttl", ATTR_HOLD_EXPIRES_AT))
                    .expressionAttributeValues(Map.of(
                            ":available", s(SeatStatus.AVAILABLE.name()),
                            ":held", s(SeatStatus.HELD.name()),
                            ":holdId", s(holdId)))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new HoldNotFoundException(
                    "No active hold " + holdId + " on seat " + seatId + " of event " + eventId);
        }
    }

    /**
     * Atomically transition a seat from {@link SeatStatus#HELD} to {@link SeatStatus#SOLD}.
     * The condition checks all three of: currently HELD, held under this {@code holdId},
     * and the hold not yet expired ({@code holdExpiresAt > now}). The expiry check must
     * live here in the write itself: DynamoDB TTL deletes lazily (possibly much later),
     * so without it an expired-but-not-yet-deleted hold could still be confirmed.
     *
     * <p>Removes {@code holdExpiresAt} so the TTL can never delete a SOLD seat;
     * {@code holdId} is kept as the audit trail of the winning hold.
     *
     * @throws HoldNotFoundException if the seat is not HELD under this {@code holdId},
     *                               or the hold has expired
     */
    public void confirmPurchase(String eventId, String seatId, String holdId, Instant now) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ATTR_PK, s(pk(eventId)),
                            ATTR_SK, s(sk(seatId))))
                    .updateExpression("SET #status = :sold REMOVE #ttl")
                    .conditionExpression("#status = :held AND #holdId = :holdId AND #ttl > :now")
                    .expressionAttributeNames(Map.of(
                            "#status", ATTR_STATUS,
                            "#holdId", ATTR_HOLD_ID,
                            "#ttl", ATTR_HOLD_EXPIRES_AT))
                    .expressionAttributeValues(Map.of(
                            ":sold", s(SeatStatus.SOLD.name()),
                            ":held", s(SeatStatus.HELD.name()),
                            ":holdId", s(holdId),
                            ":now", n(now.getEpochSecond())))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            throw new HoldNotFoundException(
                    "No active hold " + holdId + " on seat " + seatId + " of event " + eventId);
        }
    }

    /** Read a seat, or {@code null} if it does not exist. */
    public Seat getSeat(String eventId, String seatId) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        ATTR_PK, s(pk(eventId)),
                        ATTR_SK, s(sk(seatId))))
                .consistentRead(true)
                .build())
                .item();

        if (item == null || item.isEmpty()) {
            return null;
        }
        AttributeValue ttl = item.get(ATTR_HOLD_EXPIRES_AT);
        return new Seat(
                item.get(ATTR_EVENT_ID).s(),
                item.get(ATTR_SEAT_ID).s(),
                SeatStatus.valueOf(item.get(ATTR_STATUS).s()),
                item.containsKey(ATTR_HOLD_ID) ? item.get(ATTR_HOLD_ID).s() : null,
                ttl != null ? Instant.ofEpochSecond(Long.parseLong(ttl.n())) : null);
    }

    /**
     * How many of an event's seats are still {@link SeatStatus#AVAILABLE}. A count, not a
     * listing: {@code Select.COUNT} returns numbers, never items, so a stadium-sized event
     * costs read capacity but not memory. The filter runs server-side after the key match,
     * which is what makes {@code count()} the post-filter figure and what makes the paging
     * loop necessary — DynamoDB pages on the 1 MB it <em>read</em>, not on what survived
     * the filter, so a page can legitimately report a count of zero and still have a next
     * key. Feeds the {@code seats.remaining} gauge; not on any request path.
     */
    public long countAvailable(String eventId) {
        long available = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            QueryResponse page = dynamo.query(QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("#pk = :pk AND begins_with(#sk, :seatPrefix)")
                    .filterExpression("#status = :available")
                    .expressionAttributeNames(Map.of(
                            "#pk", ATTR_PK,
                            "#sk", ATTR_SK,
                            "#status", ATTR_STATUS))
                    .expressionAttributeValues(Map.of(
                            ":pk", s(pk(eventId)),
                            ":seatPrefix", s(sk("")),
                            ":available", s(SeatStatus.AVAILABLE.name())))
                    .select(Select.COUNT)
                    .exclusiveStartKey(startKey)
                    .build());
            available += page.count();
            startKey = page.hasLastEvaluatedKey() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return available;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
