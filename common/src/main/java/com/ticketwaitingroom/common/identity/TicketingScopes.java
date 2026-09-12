package com.ticketwaitingroom.common.identity;

/**
 * The OAuth2 scope contract between the Cognito user pool and every service that checks
 * it.
 *
 * <p>Every HTTP service verifies the caller's JWT itself — signature, issuer, expiry —
 * against the pool's published keys. What a valid token is then <em>allowed</em> to do is
 * decided by two claims:
 *
 * <ul>
 *   <li>{@code sub} — who the caller is. Stored as {@code customer_ref} on orders and
 *       tickets and matched for equality, never parsed. A customer may only act on rows
 *       carrying their own.</li>
 *   <li>{@code scope} — what kind of caller this is. A customer token has none of ours. A
 *       token minted for the saga or an operator through the machine-to-machine app client
 *       carries {@link #OPS}, and that is what unlocks the endpoints a customer must never
 *       reach directly: seeding seats, admitting from the queue, and the saga's own steps
 *       (hold, order, charge, confirm).</li>
 * </ul>
 *
 * <p>Same reasoning as {@code SeatTableSchema} and {@code TicketQrObjects}: the scope name
 * is configured on a Cognito resource server, which no compiler reads, and checked in four
 * services. A mismatch is not a compile error and not a runtime error — it is every saga
 * step answering 403 while the pool, the token and the code each look correct on their
 * own.
 *
 * <p>Spring exposes a scope as the authority {@code SCOPE_<scope>}; each service's
 * {@code SecurityConfig} adds the prefix where it checks. It is not added here because
 * this module is framework-free and shared with the Lambdas.
 */
public final class TicketingScopes {

    /** Resource server identifier in the pool; the scope below is namespaced under it. */
    public static final String RESOURCE_SERVER = "ticketing";

    /** Operator and saga access. Never granted to a customer's token. */
    public static final String OPS = RESOURCE_SERVER + "/ops";

    private TicketingScopes() {
    }
}
