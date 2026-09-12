package com.ticketwaitingroom.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts only Cognito <em>access</em> tokens.
 *
 * <p>A user pool signs two kinds of token with the same key. The ID token is for the
 * client that logged in — it carries the profile, and its audience is the app client. The
 * access token is for APIs. Signature, issuer and expiry are identical between them, so a
 * decoder that checks only those would accept an ID token here, and a token that was only
 * ever meant to reach a browser could be replayed against a service. {@code token_use} is
 * Cognito's own discriminator, and this is the one place it is read.
 */
final class AccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    static final String CLAIM = "token_use";
    static final String ACCESS = "access";

    private static final OAuth2Error NOT_AN_ACCESS_TOKEN = new OAuth2Error(
            OAuth2ErrorCodes.INVALID_TOKEN, "Not an access token: " + CLAIM + " must be '" + ACCESS + "'", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return ACCESS.equals(token.getClaimAsString(CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(NOT_AN_ACCESS_TOKEN);
    }
}
