package com.ticketwaitingroom.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokenValidatorTest {

    private final AccessTokenValidator validator = new AccessTokenValidator();

    @Test
    void acceptsAnAccessToken() {
        assertThat(validator.validate(token("access")).hasErrors()).isFalse();
    }

    @Test
    void rejectsAnIdToken() {
        // Same key, same issuer, same expiry as an access token -- only this claim tells
        // them apart, which is exactly why a default decoder would have let it through.
        var result = validator.validate(token("id"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }

    @Test
    void rejectsATokenWithNoTokenUseClaimAtAll() {
        Jwt foreign = Jwt.withTokenValue("t").header("alg", "RS256").subject("someone").build();

        assertThat(validator.validate(foreign).hasErrors())
                .as("a token from any issuer that does not stamp token_use is not ours")
                .isTrue();
    }

    private static Jwt token(String tokenUse) {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("b4c1e0a2-1111-4000-8000-000000000009")
                .claim(AccessTokenValidator.CLAIM, tokenUse)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
