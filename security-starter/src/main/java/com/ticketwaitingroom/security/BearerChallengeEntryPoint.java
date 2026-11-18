package com.ticketwaitingroom.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.util.regex.Pattern;

/**
 * Spring Security's bearer challenge without the {@code resource_metadata} parameter.
 *
 * <p>Spring Security 7 adds {@code resource_metadata="<scheme>://<host>/.well-known/oauth-protected-resource"}
 * (RFC 9728) to every 401, built from the request's own URL. Behind API Gateway that host is
 * the <em>internal ALB's</em> DNS name, because the private integration sends it as {@code Host}.
 * So every unauthenticated caller on the internet learned the internal load balancer's name
 * (found on the first live session, 2026-11-18). The parameter cannot be switched off: a
 * resolver may change its value but not omit it, and {@code null} prints {@code "null"}.
 *
 * <p>So the 401 is still Spring's own, with its error code, description and status per RFC 6750,
 * and only that one parameter is removed from the header on its way out. Nothing here
 * advertises metadata the service does not serve (see
 * {@link ProtectedResourceMetadataRefusal}).
 */
final class BearerChallengeEntryPoint implements AuthenticationEntryPoint {

    // Always the last parameter, its value a URL without quotes: ", resource_metadata=\"…\"",
    // or " resource_metadata=\"…\"" when it is the only one.
    private static final Pattern RESOURCE_METADATA = Pattern.compile("(,\\s*|\\s+)resource_metadata=\"[^\"]*\"");

    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        delegate.commence(request, new WithoutResourceMetadata(response), exception);
    }

    static String withoutResourceMetadata(String challenge) {
        return RESOURCE_METADATA.matcher(challenge).replaceAll("");
    }

    private static final class WithoutResourceMetadata extends HttpServletResponseWrapper {

        WithoutResourceMetadata(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void addHeader(String name, String value) {
            super.addHeader(name, filtered(name, value));
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, filtered(name, value));
        }

        private static String filtered(String name, String value) {
            return HttpHeaders.WWW_AUTHENTICATE.equalsIgnoreCase(name) ? withoutResourceMetadata(value) : value;
        }
    }
}
