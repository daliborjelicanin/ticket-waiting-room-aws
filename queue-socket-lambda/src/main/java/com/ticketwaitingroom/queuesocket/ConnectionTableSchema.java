package com.ticketwaitingroom.queuesocket;

/**
 * The connection-registry table contract: who is listening, on which socket, for which
 * queue token.
 *
 * <p>Unlike {@code SeatTableSchema} this does <em>not</em> live in {@code common}, and the
 * rule there is why: a contract belongs in {@code common} when more than one deployable
 * must agree on it. The only Java that touches this table is the notifier, in this module,
 * so promoting it would buy nothing and would export the DynamoDB SDK from {@code common}
 * to do it.
 *
 * <p>Item shape:
 * <pre>
 *   connectionId = API Gateway's id for the socket   (HASH key — the only thing
 *                                                     $disconnect is given)
 *   eventId      = which event's queue                (GSI HASH)
 *   token        = the queue token                    (GSI RANGE)
 *   connectedAt  = epoch seconds, for debugging a stuck socket
 *   expiresAt    = epoch-seconds TTL, connectedAt + 24h
 * </pre>
 *
 * <p><strong>Nothing in this codebase writes that item.</strong> {@code $connect} and
 * {@code $disconnect} are API Gateway service integrations onto DynamoDB, so the
 * authoritative writer is a mapping template living in
 * {@code docs/runbook-websocket-queue.md} and, from phase 2, in Terraform. That is the one
 * seam in this module where a rename cannot be a compile error, which is why
 * {@code QueueSocketIT} seeds its rows from these constants in the template's exact shape
 * rather than through a helper: the test is what makes the two halves disagree loudly.
 *
 * <p>The {@link #GSI_BY_TOKEN} index is projected {@code KEYS_ONLY} on purpose: the base
 * table's key <em>is</em> the {@code connectionId}, which is the only thing the notifier
 * needs from the lookup, so the index carries no payload attributes at all and costs the
 * minimum write amplification — which is charged to every connect, since a PutItem into a
 * table with one GSI is two write units rather than one.
 */
final class ConnectionTableSchema {

    static final String ATTR_CONNECTION_ID = "connectionId";
    static final String ATTR_EVENT_ID = "eventId";
    static final String ATTR_TOKEN = "token";
    static final String ATTR_CONNECTED_AT = "connectedAt";

    /**
     * The DynamoDB TTL attribute, enabled on the table itself. Epoch <em>seconds</em> as a
     * Number — a String value, or milliseconds, is accepted on write and then silently
     * ignored by TTL forever. The mapping template therefore divides
     * {@code $context.requestTimeEpoch} by 1000 before adding the lifetime.
     *
     * <p>TTL is the backstop, not the mechanism: {@code $disconnect} is what normally
     * prunes a row, but API Gateway does not guarantee it fires, and a registry that only
     * ever grows turns every push into a pile of 410s.
     */
    static final String ATTR_EXPIRES_AT = "expiresAt";

    /**
     * Matches {@code waiting-room.key-ttl}: a row cannot usefully outlive the Redis keys
     * that give the token meaning. Computed by the {@code $connect} mapping template, not
     * here — this constant exists so the test can assert the template got it right.
     */
    static final long LIFETIME_SECONDS = 24 * 60 * 60;

    /** HASH {@code eventId}, RANGE {@code token}, projection KEYS_ONLY. */
    static final String GSI_BY_TOKEN = "by-token";

    private ConnectionTableSchema() {
    }
}
