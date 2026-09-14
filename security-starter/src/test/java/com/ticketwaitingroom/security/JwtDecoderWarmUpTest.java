package com.ticketwaitingroom.security;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.text.ParseException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The warm-up's contract is narrow: hand the decoder a token that exercises every fetch
 * and cannot pass, and survive whatever the decoder says about it. Whether it actually
 * shortens the first real request is a number read off a Fargate task's log, not a unit test.
 */
class JwtDecoderWarmUpTest {

    private static final String ISSUER = "https://cognito-idp.eu-central-1.amazonaws.com/eu-central-1_TEST";

    @Test
    void theProbeIsAWellFormedTokenForTheIssuerThatNoRealKeyCanHaveSigned() throws ParseException {
        SignedJWT token = SignedJWT.parse(JwtDecoderWarmUp.unverifiableToken(ISSUER));

        assertThat(token.getHeader().getKeyID()).isEqualTo("warm-up");
        assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(token.getJWTClaimsSet().getIssuer()).isEqualTo(ISSUER);
        assertThat(token.getJWTClaimsSet().getStringClaim("token_use")).isEqualTo("access");
        assertThat(token.getJWTClaimsSet().getExpirationTime()).isAfter(Instant.now());
        assertThat(token.getSignature().decode()).containsOnly(0);
    }

    @Test
    void aRejectedProbeIsTheExpectedOutcomeAndIsNotAnError() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("no matching key"));

        assertThatCode(() -> new JwtDecoderWarmUp(decoder, ISSUER).warmUp()).doesNotThrowAnyException();
        verify(decoder).decode(anyString());
    }

    @Test
    void anUnreachableIssuerIsLoggedAndSwallowed() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode(anyString())).thenThrow(new IllegalStateException("connection refused"));

        assertThatCode(() -> new JwtDecoderWarmUp(decoder, ISSUER).warmUp()).doesNotThrowAnyException();
    }
}
