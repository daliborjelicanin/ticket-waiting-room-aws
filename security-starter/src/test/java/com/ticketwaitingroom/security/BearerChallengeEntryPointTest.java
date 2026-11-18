package com.ticketwaitingroom.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401 is Spring Security's own (error code, description, status per RFC 6750) with only
 * {@code resource_metadata} removed: that parameter carried the request's Host, which behind
 * the private integration is the internal ALB's DNS name.
 */
class BearerChallengeEntryPointTest {

    private static final String INTERNAL_HOST = "internal-twr-test-alb.eu-central-1.elb.amazonaws.com";

    private final BearerChallengeEntryPoint entryPoint = new BearerChallengeEntryPoint();

    @Test
    void anInvalidTokenKeepsItsErrorAndLosesTheHost() {
        MockHttpServletResponse response = commence(new InvalidBearerTokenException("Malformed token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .isEqualTo("Bearer error=\"invalid_token\", error_description=\"Malformed token\", "
                        + "error_uri=\"https://tools.ietf.org/html/rfc6750#section-3.1\"")
                .doesNotContain(INTERNAL_HOST)
                .doesNotContain("resource_metadata");
    }

    @Test
    void aMissingTokenIsAPlainBearerChallenge() {
        MockHttpServletResponse response = commence(new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void anErrorsOwnStatusAndScopeAreKept() {
        // Spring maps insufficient_scope to 403 with the scope named; the wrapper changes neither.
        MockHttpServletResponse response = commence(new OAuth2AuthenticationException(
                BearerTokenErrors.insufficientScope("The request requires higher privileges", "ticketing/ops")));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate"))
                .contains("error=\"insufficient_scope\"")
                .contains("scope=\"ticketing/ops\"")
                .doesNotContain("resource_metadata");
    }

    @Test
    void onlyTheMetadataParameterIsRemoved() {
        String metadata = "resource_metadata=\"https://" + INTERNAL_HOST + "/.well-known/oauth-protected-resource\"";

        assertThat(BearerChallengeEntryPoint.withoutResourceMetadata("Bearer error=\"invalid_token\", " + metadata))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(BearerChallengeEntryPoint.withoutResourceMetadata("Bearer " + metadata))
                .isEqualTo("Bearer");
    }

    private MockHttpServletResponse commence(AuthenticationException exception) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/events/e1/seats/A1/hold");
        request.setServerName(INTERNAL_HOST);
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(request, response, exception);
        return response;
    }
}
