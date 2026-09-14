package com.ticketwaitingroom.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The chain as a service experiences it: a throwaway application with one open endpoint
 * and one the service has declared ops-only through {@link EndpointAuthorization}, driven
 * by real requests through the real filters. The token is supplied by spring-security-test's
 * {@code jwt()} post-processor — no key material, no network, no decoder involved.
 */
@SpringBootTest(
        classes = TicketingSecurityFilterChainTest.App.class,
        properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://cognito.test.invalid/")
@AutoConfigureMockMvc
class TicketingSecurityFilterChainTest {

    private static final String CUSTOMER = "b4c1e0a2-1111-4000-8000-000000000009";

    @Autowired
    private MockMvc mockMvc;

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(CUSTOMER).claim("scope", "aws.cognito.signin.user.admin"));
    }

    private static RequestPostProcessor ops() {
        return jwt().jwt(token -> token.subject("m2m-client-id").claim("scope", "ticketing/ops"));
    }

    @Test
    void anonymousRequestsAre401WithABearerChallenge() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
    }

    @Test
    void anyValidTokenReachesAnUndeclaredEndpointAsItsOwnSubject() throws Exception {
        mockMvc.perform(get("/me").with(customer()))
                .andExpect(status().isOk())
                .andExpect(content().string(CUSTOMER));
    }

    @Test
    void aDeclaredOpsEndpointRefusesACustomerAndAdmitsTheOpsScope() throws Exception {
        mockMvc.perform(post("/ops").with(customer())).andExpect(status().isForbidden());
        mockMvc.perform(post("/ops").with(ops())).andExpect(status().isOk());
        mockMvc.perform(post("/ops")).andExpect(status().isUnauthorized());
    }

    @Test
    void theHealthEndpointIsTheOneAnonymousDoor() throws Exception {
        // The ALB probes this with no token. Only GET, only health, only the status word.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
        mockMvc.perform(post("/actuator/health")).andExpect(status().isUnauthorized());
    }

    @Test
    void theOldHealthCheckPathIsRefusedNotMissing() throws Exception {
        // Before the health endpoint existed the probe read a 401 from GET / as "alive".
        // Pinned so a target group still on the old path degrades to unhealthy-by-401 being
        // inside its 200-499 matcher, never to a 404 or a 500 that looks like a real fault.
        mockMvc.perform(get("/")).andExpect(status().isUnauthorized());
    }

    @SpringBootApplication
    static class App {

        @Bean
        EndpointAuthorization opsEndpoints() {
            return registry -> registry.requestMatchers(HttpMethod.POST, "/ops").hasAuthority(TicketingAuthorities.OPS);
        }

        @RestController
        static class Endpoints {

            @GetMapping("/me")
            String me(@AuthenticationPrincipal Jwt caller) {
                return caller.getSubject();
            }

            @PostMapping("/ops")
            String ops() {
                return "ok";
            }
        }
    }
}
