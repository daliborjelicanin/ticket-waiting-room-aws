package com.ticketwaitingroom.security;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>Runs after {@link ApplicationReadyEvent} on a virtual thread, so it never delays the
 * port opening and never fails startup: a pool that is unreachable at boot is logged and
 * met again by the first real request, exactly as before.
 */
final class JwtDecoderWarmUp implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(JwtDecoderWarmUp.class);

    private final JwtDecoder decoder;
    private final String issuer;

    JwtDecoderWarmUp(JwtDecoder decoder, String issuer) {
        this.decoder = decoder;
        this.issuer = issuer;
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        Thread.ofVirtual().name("jwt-warm-up").start(this::warmUp);
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
