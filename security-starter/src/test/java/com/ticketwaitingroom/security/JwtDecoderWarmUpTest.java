package com.ticketwaitingroom.security;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void theRunnerReturnsOnlyOnceTheDecoderIsWarm() throws InterruptedException {
        AtomicBoolean warmed = new AtomicBoolean();
        JwtDecoder slow = _ -> {
            sleep(Duration.ofMillis(300));
            warmed.set(true);
            throw new BadJwtException("no matching key");
        };

        new JwtDecoderWarmUp(slow, ISSUER).run(new DefaultApplicationArguments());

        assertThat(warmed).isTrue();
    }

    @Test
    void aHungIssuerHoldsReadinessOnlyForTheWait() throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        JwtDecoder hung = _ -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new BadJwtException("released");
        };

        long started = System.nanoTime();
        try {
            new JwtDecoderWarmUp(hung, ISSUER, Duration.ofMillis(200)).run(new DefaultApplicationArguments());
            // Waited the bound, then let go: not the hung fetch's lifetime.
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(200L, 2_000L);
        } finally {
            never.countDown();
        }
    }

    /**
     * The claim D7 rests on, through a real {@link SpringApplication}: Boot reports the
     * application ready only after the warm-up has finished, so the ALB's readiness probe
     * cannot send a customer to a cold decoder.
     */
    @Test
    void bootReportsReadyOnlyAfterTheWarmUp() {
        SpringApplication application = new SpringApplication(SlowWarmUpApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        AtomicReference<Boolean> warmWhenReady = new AtomicReference<>();
        application.addListeners((ApplicationListener<AvailabilityChangeEvent<?>>) event -> {
            if (event.getState() == ReadinessState.ACCEPTING_TRAFFIC) {
                warmWhenReady.set(SlowWarmUpApp.WARMED.get());
            }
        });

        try (ConfigurableApplicationContext ignored = application.run()) {
            assertThat(warmWhenReady.get()).as("decoder warm at ACCEPTING_TRAFFIC").isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SlowWarmUpApp {

        static final AtomicBoolean WARMED = new AtomicBoolean();

        @Bean
        JwtDecoderWarmUp jwtDecoderWarmUp() {
            JwtDecoder slow = _ -> {
                sleep(Duration.ofMillis(300));
                WARMED.set(true);
                throw new BadJwtException("no matching key");
            };
            return new JwtDecoderWarmUp(slow, ISSUER);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
