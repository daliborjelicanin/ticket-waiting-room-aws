package com.ticketwaitingroom.holdcleanup;

import com.ticketwaitingroom.common.events.SeatHoldExpiredEvent;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_EVENT_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_HOLD_EXPIRES_AT;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_HOLD_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_PK;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_SEAT_ID;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_SK;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.ATTR_STATUS;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.STATUS_AVAILABLE;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.STATUS_HELD;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.pk;
import static com.ticketwaitingroom.common.inventory.SeatTableSchema.sk;

/**
 * Finds seats whose hold has lapsed and returns them to the pool.
 *
 * <p>This exists because DynamoDB TTL cannot do the job on its own, in two distinct ways.
 * TTL is <em>lazy</em> — deletion happens "typically within 48 hours" — so an expired hold
 * stays readable, and therefore un-holdable by anyone else, long after it lapsed. And when
 * TTL does fire on a HELD seat it deletes the <em>whole item</em>, not the hold, so the
 * seat would vanish from inventory rather than become available again. Reclaiming is a
 * correctness requirement; TTL is only garbage collection.
 */
@RequiredArgsConstructor
class ExpiredHoldRepository {

    private final DynamoDbClient dynamo;
    private final String tableName;

    /**
     * Every seat currently HELD under a hold that expired at or before {@code now}.
     *
     * <p>A {@code Scan} is the honest choice at this table's size: seats are stored under
     * one partition per event with no index on status, so there is nothing to Query. It
     * reads the whole table, which is fine for a learning-scale event and would not be for
     * a real one — the scale answer is a sparse GSI carrying {@code holdExpiresAt} only
     * while a seat is held, which turns this into a bounded range Query. Deliberately
     * deferred; see the runbook.
     *
     * <p>The projection is not decoration: {@code Scan} bills for the items it examines,
     * and the four attributes here are exactly what {@link #reclaim} needs to rebuild the
     * key and re-check the guard.
     */
    List<SeatHoldExpiredEvent> findExpiredHolds(Instant now) {
        return dynamo.scanPaginator(ScanRequest.builder()
                        .tableName(tableName)
                        .filterExpression("#status = :held AND #ttl <= :now")
                        .projectionExpression("#eventId, #seatId, #holdId, #ttl")
                        .expressionAttributeNames(Map.of(
                                "#status", ATTR_STATUS,
                                "#eventId", ATTR_EVENT_ID,
                                "#seatId", ATTR_SEAT_ID,
                                "#holdId", ATTR_HOLD_ID,
                                "#ttl", ATTR_HOLD_EXPIRES_AT))
                        .expressionAttributeValues(Map.of(
                                ":held", s(STATUS_HELD),
                                ":now", n(now.getEpochSecond())))
                        .build())
                .items().stream()
                .map(ExpiredHoldRepository::toExpiredHold)
                .toList();
    }

    /**
     * Atomically return one expired hold's seat to {@link
     * com.ticketwaitingroom.common.inventory.SeatTableSchema#STATUS_AVAILABLE}.
     *
     * <p>The scan that produced {@code hold} is a stale read by the time this write lands,
     * so all three guards re-check at write time rather than trusting it:
     *
     * <ul>
     *   <li>{@code #status = :held} — never resurrect a seat that has since been SOLD.
     *   <li>{@code #holdId = :holdId} — the hold seen by the scan is still the hold on the
     *       item. Without this, a seat that expired and was legitimately re-held in the
     *       intervening milliseconds would have the <em>new</em> owner's hold torn up.
     *   <li>{@code #ttl <= :now} — re-check expiry against a fresh clock, so a hold that
     *       was extended after the scan is left alone.
     * </ul>
     *
     * <p>Losing this race is normal operation, not an error: a purchase landing in the same
     * instant is exactly the contention this design expects, and DynamoDB serialises the
     * two conditional writes so precisely one takes effect.
     *
     * @return {@code true} if the seat was reclaimed, {@code false} if another writer won
     */
    boolean reclaim(SeatHoldExpiredEvent hold, Instant now) {
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            ATTR_PK, s(pk(hold.eventId())),
                            ATTR_SK, s(sk(hold.seatId()))))
                    .updateExpression("SET #status = :available REMOVE #holdId, #ttl")
                    .conditionExpression("#status = :held AND #holdId = :holdId AND #ttl <= :now")
                    .expressionAttributeNames(Map.of(
                            "#status", ATTR_STATUS,
                            "#holdId", ATTR_HOLD_ID,
                            "#ttl", ATTR_HOLD_EXPIRES_AT))
                    .expressionAttributeValues(Map.of(
                            ":available", s(STATUS_AVAILABLE),
                            ":held", s(STATUS_HELD),
                            ":holdId", s(hold.holdId()),
                            ":now", n(now.getEpochSecond())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException lostRace) {
            return false;
        }
    }

    private static SeatHoldExpiredEvent toExpiredHold(Map<String, AttributeValue> item) {
        return new SeatHoldExpiredEvent(
                item.get(ATTR_EVENT_ID).s(),
                item.get(ATTR_SEAT_ID).s(),
                item.get(ATTR_HOLD_ID).s(),
                Instant.ofEpochSecond(Long.parseLong(item.get(ATTR_HOLD_EXPIRES_AT).n())));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
