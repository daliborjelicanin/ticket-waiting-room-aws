package com.ticketwaitingroom.security;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Pays the first token's cost before the first customer does.
 *
 * <p>Measured on a 0.25 vCPU task in slice 6: the first token a service ever verified took
 * <b>12.8 s</b>, against 0.1 s for every one after. Nothing in that number is the token.
 * It is the decoder being built ({@code withIssuerLocation} fetches the pool's discovery
 * document), the JWKS fetch behind it, two TLS handshakes to Cognito, and the JIT meeting
 * Nimbus for the first time — all of it deferred by design to the first request, because
 * the decoder is lazy so the service can boot with the pool unreachable.
 *
 * <p>This keeps that laziness and moves the first request forward to startup. It presents
 * the decoder with a syntactically valid JWT signed by a key the pool has never issued.
 * Verifying it forces every step above — including the key lookup, since Nimbus has to
 * fetch the JWKS to discover the {@code kid} is not there — and then fails, as it must.
 * The failure is the expected outcome; a token that <em>passed</em> would be the alarm.
 *
 * <p><b>Part of readiness.</b> It runs as an {@link ApplicationRunner}: after the port is
 * open and liveness is up, but <em>before</em> Boot reports the application ready, since
 * Boot publishes {@code ReadinessState.ACCEPTING_TRAFFIC} only once every runner has
 * returned. With observability-starter's probes on, the ALB's readiness check therefore
 * answers 503 until the decoder is warm, and no customer request pays the 12.8 s (D7).
 *
 * <p>It still never fails startup, and never holds it for long. The work runs on a virtual
 * thread that the runner waits on for at most {@link #DEFAULT_WAIT}. Nimbus's default
 * client sets no read timeout, so an issuer that accepts the connection and then goes
 * quiet must not keep the task unready until ECS replaces it. Past the wait, or with the
 * pool unreachable, the task reports ready anyway, and the first real request pays, exactly
 * as before. Gating readiness on the pool for longer would mark every task unhealthy the
 * moment Cognito hiccupped, for a reason that says nothing about the task.
 */
final class JwtDecoderWarmUp implements ApplicationRunner {

    /** Above the slowest warm-up measured on 0.25 vCPU (13.6 s, Phase 1), well under ECS's grace period. */
    static final Duration DEFAULT_WAIT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderWarmUp.class);

    private final JwtDecoder decoder;
    private final String issuer;
    private final Duration wait;

    JwtDecoderWarmUp(JwtDecoder decoder, String issuer) {
        this(decoder, issuer, DEFAULT_WAIT);
    }

    JwtDecoderWarmUp(JwtDecoder decoder, String issuer, Duration wait) {
        this.decoder = decoder;
        this.issuer = issuer;
        this.wait = wait;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) throws InterruptedException {
        Thread worker = Thread.ofVirtual().name("jwt-warm-up").start(this::warmUp);
        if (!worker.join(wait)) {
            // Left running: it finishes the fetch in the background, so the first real token
            // still finds the work begun. Readiness does not wait for it.
            log.warn("JWT warm-up still running after {} s; reporting ready, the first real token may pay for it",
                    wait.toSeconds());
        }
    }

    void warmUp() {
        long started = System.nanoTime();
        try {
            decoder.decode(unverifiableToken(issuer));
            log.warn("JWT warm-up token was ACCEPTED — the decoder is not verifying signatures");
        } catch (JwtDecoderInitializationException notBuilt) {
            // The discovery document could not be fetched, so the decoder does not exist yet.
            log.warn("JWT warm-up could not reach the issuer; the first real token will pay for it", notBuilt);
        } catch (JwtException rejected) {
            if (causedByIo(rejected)) {
                // Built, but the JWKS fetch failed: same outcome, one step further along.
                log.warn("JWT warm-up could not fetch the issuer's keys; the first real token will pay for it", rejected);
            } else {
                log.info("JWT decoder warm: issuer metadata and keys fetched in {} ms ({})",
                        (System.nanoTime() - started) / 1_000_000, rejected.getMessage());
            }
        } catch (RuntimeException unexpected) {
            log.warn("JWT warm-up failed; the first real token will pay for it", unexpected);
        }
    }

    private static boolean causedByIo(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * A well-formed RS256 JWT for this issuer, far from expiry, whose {@code kid} names a key
     * that does not exist and whose signature is all zero bytes. Every check it can reach
     * before the signature — parse, issuer, timestamps — passes; the key lookup is what fails.
     */
    static String unverifiableToken(String issuer) {
        String header = "{\"alg\":\"RS256\",\"kid\":\"warm-up\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\"" + issuer + "\",\"token_use\":\"access\",\"exp\":4102444800}";
        return base64Url(header) + "." + base64Url(payload) + "." + base64Url(new byte[256]);
    }

    private static String base64Url(String text) {
        return base64Url(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
