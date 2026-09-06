package com.ticketwaitingroom.queuesocket;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.List;
import java.util.Map;

import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_CONNECTION_ID;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_EVENT_ID;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.ATTR_TOKEN;
import static com.ticketwaitingroom.queuesocket.ConnectionTableSchema.GSI_BY_TOKEN;

/**
 * Which sockets belong to which queue token, as far as the notifier is concerned.
 *
 * <p>API Gateway remembers nothing between {@code $connect} and the moment a server wants
 * to push: it hands out a {@code connectionId} and expects the application to keep the
 * mapping. Without this table the notifier has a token and no way to reach it, so the
 * registry is not bookkeeping — it is the whole reason a push is possible.
 *
 * <p><strong>Nothing here writes a row.</strong> {@code $connect} and {@code $disconnect}
 * are API Gateway service integrations that call DynamoDB directly, with no function in
 * between — see {@code docs/runbook-websocket-queue.md}. This class is the read side plus
 * the one delete the notifier itself owns, and {@link ConnectionTableSchema} is where the
 * two halves are documented as agreeing.
 */
@RequiredArgsConstructor
class ConnectionRegistry {

    private final DynamoDbClient dynamo;
    private final String tableName;

    /**
     * Forget a socket that answered 410.
     *
     * <p>Idempotent by construction — DynamoDB treats a delete of an absent key as a
     * success — which matters because the {@code $disconnect} integration deletes the same
     * row, sometimes concurrently with this.
     */
    void remove(String connectionId) {
        dynamo.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(ATTR_CONNECTION_ID, s(connectionId)))
                .build());
    }

    /**
     * Every socket currently registered for one token, usually zero or one.
     *
     * <p>A Query on {@link ConnectionTableSchema#GSI_BY_TOKEN}, whose KEYS_ONLY projection
     * returns exactly the base key: the {@code connectionId}. Nothing else is read, so the
     * index needs no projected attributes.
     *
     * <p><strong>Eventually consistent, and it has to be</strong> — DynamoDB does not offer
     * a strongly consistent read of a global secondary index. A token that connects and is
     * admitted within the same few milliseconds can therefore come back empty and get no
     * frame. That is accepted rather than worked around: the socket is an optimisation over
     * {@code GET /events/{eventId}/queue/{token}}, which remains the source of truth, so
     * the worst case is a client that learns of its admission from the poll it was going to
     * make anyway.
     *
     * <p>Paginated, even though one page holds far more tabs than a person will ever open.
     * A Query returns at most 1 MB and reports the rest in {@code lastEvaluatedKey}; a
     * plain {@code query} silently drops it, which would be a token that is half-notified
     * with nothing anywhere saying so. Nothing currently caps how many sockets one token
     * may register — see the runbook's Phase 2 notes, where that cap belongs.
     */
    List<String> connectionsFor(String eventId, String token) {
        return dynamo.queryPaginator(QueryRequest.builder()
                        .tableName(tableName)
                        .indexName(GSI_BY_TOKEN)
                        .keyConditionExpression("#eventId = :eventId AND #token = :token")
                        .expressionAttributeNames(Map.of(
                                "#eventId", ATTR_EVENT_ID,
                                "#token", ATTR_TOKEN))
                        .expressionAttributeValues(Map.of(
                                ":eventId", s(eventId),
                                ":token", s(token)))
                        .build())
                .items().stream()
                .map(item -> item.get(ATTR_CONNECTION_ID).s())
                .toList();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
