package com.ticketwaitingroom.waitingroom.web;

import com.ticketwaitingroom.common.exceptions.UnknownQueueTokenException;
import com.ticketwaitingroom.common.identity.TicketingScopes;
import com.ticketwaitingroom.waitingroom.config.SecurityConfig;
import com.ticketwaitingroom.waitingroom.queue.QueuePlace;
import com.ticketwaitingroom.waitingroom.queue.QueueStatus;
import com.ticketwaitingroom.waitingroom.queue.WaitingRoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives requests through the real {@link SecurityConfig}, so the 401s and 403s below are
 * produced by the filter chain rather than assumed. The token itself is supplied by
 * spring-security-test's {@code jwt()} post-processor, which sets the authentication
 * directly — no key material, no network, and no decoder involved.
 */
@WebMvcTest(WaitingRoomController.class)
@Import(SecurityConfig.class)
class WaitingRoomControllerTest {

    /** Shaped like a Cognito {@code sub}, rather than a friendly label. */
    private static final String CUSTOMER = "b4c1e0a2-1111-4000-8000-000000000009";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitingRoomService waitingRoomService;

    /** A customer's access token: Cognito's own sign-in scope, none of ours. */
    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(CUSTOMER).claim("scope", "aws.cognito.signin.user.admin"));
    }

    /** A machine-to-machine token — the saga's, or an operator's from the CLI. */
    private static RequestPostProcessor ops() {
        return jwt().jwt(token -> token.subject("m2m-client-id").claim("scope", TicketingScopes.OPS));
    }

    @Test
    void joinActsAsTheTokensSubjectAndReturns201() throws Exception {
        when(waitingRoomService.join("evt-1", CUSTOMER))
                .thenReturn(new QueuePlace("evt-1", "tok-abc", QueueStatus.WAITING, 7L, 42, null));

        mockMvc.perform(post("/events/{eventId}/queue", "evt-1").with(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok-abc"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(7))
                .andExpect(jsonPath("$.queueLength").value(42));

        verify(waitingRoomService).join("evt-1", CUSTOMER);
    }

    @Test
    void joinWithoutATokenIs401AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/events/{eventId}/queue", "evt-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));

        verify(waitingRoomService, never()).join(anyString(), anyString());
    }

    @Test
    void placeIsScopedToTheCallerAndTakesNoTokenFromTheUrl() throws Exception {
        when(waitingRoomService.place("evt-1", CUSTOMER))
                .thenReturn(new QueuePlace("evt-1", "tok-abc", QueueStatus.ADMITTED, null, 0,
                        Instant.parse("2026-07-05T12:00:00Z")));

        mockMvc.perform(get("/events/{eventId}/queue", "evt-1").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.admissionExpiresAt").value("2026-07-05T12:00:00Z"));
    }

    @Test
    void placeReturns404WhenTheCallerHoldsNoPlace() throws Exception {
        when(waitingRoomService.place("evt-1", CUSTOMER))
                .thenThrow(new UnknownQueueTokenException("No place in the waiting room for event evt-1"));

        mockMvc.perform(get("/events/{eventId}/queue", "evt-1").with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not in the waiting room"));
    }

    @Test
    void admitRequiresTheOpsScope() throws Exception {
        when(waitingRoomService.admitNext("evt-1", 3))
                .thenReturn(new WaitingRoomService.Admission(
                        List.of("tok-a", "tok-b"), Instant.parse("2026-07-05T12:00:00Z")));

        mockMvc.perform(post("/events/{eventId}/queue/admit", "evt-1").with(ops())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens[0]").value("tok-a"))
                .andExpect(jsonPath("$.tokens[1]").value("tok-b"))
                .andExpect(jsonPath("$.admitted").value(2));
    }

    @Test
    void aCustomerCannotAdmitEvenWithAValidToken() throws Exception {
        // The whole point of the ops scope: a signed-in customer holds a perfectly good
        // token from the same pool, and it is still not enough to pop the queue.
        mockMvc.perform(post("/events/{eventId}/queue/admit", "evt-1").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3}"))
                .andExpect(status().isForbidden());

        verify(waitingRoomService, never()).admitNext(anyString(), anyInt());
    }

    @Test
    void admitReturns400ForAnOutOfRangeCount() throws Exception {
        when(waitingRoomService.admitNext("evt-1", 0))
                .thenThrow(new IllegalArgumentException("Admit count must be between 1 and 500, was 0"));

        mockMvc.perform(post("/events/{eventId}/queue/admit", "evt-1").with(ops())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }
}
