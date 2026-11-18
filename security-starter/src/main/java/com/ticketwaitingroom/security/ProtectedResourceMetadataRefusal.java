package com.ticketwaitingroom.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Answers {@code GET /.well-known/oauth-protected-resource/**} with a 404, ahead of Spring
 * Security's own metadata endpoint.
 *
 * <p>Spring Security 7 installs that endpoint for every resource server, anonymous, and it
 * cannot be turned off: the filter is final, its path fixed. What it would serve is wrong
 * here twice over. Its {@code resource} is built from the request's {@code Host}, which
 * behind the private integration is the internal ALB's DNS name. And it claims
 * {@code tls_client_certificate_bound_access_tokens: true}, which Cognito's tokens are not.
 * The edge routes no {@code /.well-known} path to any service, so this closes the door from
 * inside the VPC as well (2026-11-18).
 */
final class ProtectedResourceMetadataRefusal extends OncePerRequestFilter {

    // The same matcher Spring Security's OAuth2ProtectedResourceMetadataFilter uses.
    private static final RequestMatcher METADATA = PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, "/.well-known/oauth-protected-resource/**");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (METADATA.matches(request)) {
            // A status, not sendError: an error dispatch to /error would run the chain again
            // and come back as the anonymous 401.
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
