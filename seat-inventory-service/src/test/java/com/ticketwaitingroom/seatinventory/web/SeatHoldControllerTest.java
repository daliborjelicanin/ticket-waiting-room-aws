package com.ticketwaitingroom.seatinventory.web;

import com.ticketwaitingroom.common.exceptions.HoldNotFoundException;
import com.ticketwaitingroom.common.exceptions.SeatUnavailableException;
import com.ticketwaitingroom.common.identity.TicketingScopes;
import com.ticketwaitingroom.seatinventory.config.SecurityConfig;
import com.ticketwaitingroom.seatinventory.inventory.SeatHold;
import com.ticketwaitingroom.seatinventory.inventory.SeatHoldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives requests through the real {@link SecurityConfig}; see
 * {@code WaitingRoomControllerTest} for how the token is supplied. Every endpoint here is
 * ops-only — the saga holds, confirms and releases on the customer's behalf — so the
 * interesting cases are the ones a customer's own token must not open.
 */
@WebMvcTest(SeatHoldController.class)
@Import(SecurityConfig.class)
class SeatHoldControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SeatHoldService seatHoldService;

    /** The saga's machine-to-machine token. */
    private static RequestPostProcessor saga() {
        return jwt().jwt(token -> token.subject("m2m-client-id").claim("scope", TicketingScopes.OPS));
    }

    /** A customer's access token: Cognito's own sign-in scope, none of ours. */
    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject("some-customer").claim("scope", "aws.cognito.signin.user.admin"));
    }

    @Test
    void returns201WithHoldOnSuccess() throws Exception {
        when(seatHoldService.holdSeat(eq("evt-1"), eq("A-12")))
                .thenReturn(new SeatHold("evt-1", "A-12", "hold-abc", Instant.parse("2026-07-05T12:00:00Z")));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/hold", "evt-1", "A-12").with(saga()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.seatId").value("A-12"))
                .andExpect(jsonPath("$.holdId").value("hold-abc"));
    }

    @Test
    void returns409WhenSeatUnavailable() throws Exception {
        when(seatHoldService.holdSeat(eq("evt-1"), eq("A-12")))
                .thenThrow(new SeatUnavailableException("Seat A-12 of event evt-1 is not available to hold"));

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/hold", "evt-1", "A-12").with(saga()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat unavailable"));
    }

    @Test
    void releaseReturns204OnSuccess() throws Exception {
        mockMvc.perform(delete("/events/{eventId}/seats/{seatId}/hold/{holdId}", "evt-1", "A-12", "hold-abc")
                        .with(saga()))
                .andExpect(status().isNoContent());

        verify(seatHoldService).releaseHold("evt-1", "A-12", "hold-abc");
    }

    @Test
    void releaseReturns404WhenHoldMissing() throws Exception {
        doThrow(new HoldNotFoundException("No active hold hold-abc on seat A-12 of event evt-1"))
                .when(seatHoldService).releaseHold("evt-1", "A-12", "hold-abc");

        mockMvc.perform(delete("/events/{eventId}/seats/{seatId}/hold/{holdId}", "evt-1", "A-12", "hold-abc")
                        .with(saga()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Hold not found"));
    }

    @Test
    void purchaseReturns200WithSoldSeat() throws Exception {
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/purchase", "evt-1", "A-12").with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"hold-abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.seatId").value("A-12"))
                .andExpect(jsonPath("$.status").value("SOLD"));

        verify(seatHoldService).confirmPurchase("evt-1", "A-12", "hold-abc");
    }

    @Test
    void purchaseReturns404WhenHoldMissingOrExpired() throws Exception {
        doThrow(new HoldNotFoundException("No active hold hold-abc on seat A-12 of event evt-1"))
                .when(seatHoldService).confirmPurchase("evt-1", "A-12", "hold-abc");

        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/purchase", "evt-1", "A-12").with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holdId\":\"hold-abc\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Hold not found"));
    }

    @Test
    void aCustomerCannotHoldASeatDirectly() throws Exception {
        // Seat locking is the saga's to do on the customer's behalf. A customer's own token
        // is valid and signed by the same pool, and it is still refused at the filter.
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/hold", "evt-1", "A-12").with(customer()))
                .andExpect(status().isForbidden());

        verify(seatHoldService, never()).holdSeat(any(), any());
    }

    @Test
    void anAnonymousHoldIs401() throws Exception {
        mockMvc.perform(post("/events/{eventId}/seats/{seatId}/hold", "evt-1", "A-12"))
                .andExpect(status().isUnauthorized());

        verify(seatHoldService, never()).holdSeat(any(), any());
    }
}
