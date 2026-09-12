package com.ticketwaitingroom.security;

import com.ticketwaitingroom.common.identity.TicketingScopes;

/**
 * The scopes of {@link TicketingScopes}, in the form Spring Security checks them.
 *
 * <p>A JWT's {@code scope} claim becomes a granted authority prefixed with {@code SCOPE_}.
 * That prefix is a Spring convention, not part of the contract with Cognito, which is why
 * it is added here — in the one module that knows about Spring Security — rather than in
 * {@code common}, which the framework-free Lambdas share.
 */
public final class TicketingAuthorities {

    /** Operator and saga access; see {@link TicketingScopes#OPS}. */
    public static final String OPS = "SCOPE_" + TicketingScopes.OPS;

    private TicketingAuthorities() {
    }
}
