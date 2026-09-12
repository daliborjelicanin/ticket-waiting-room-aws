package com.ticketwaitingroom.seatinventory.web;

import com.ticketwaitingroom.common.identity.TicketingScopes;
import com.ticketwaitingroom.seatinventory.config.SecurityConfig;
import com.ticketwaitingroom.seatinventory.inventory.SeatSeedService;
import com.ticketwaitingroom.seatinventory.inventory.SeatSeedService.SeedResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SeatSeedController.class)
@Import(SecurityConfig.class)
class SeatSeedControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SeatSeedService seatSeedService;

    /** An operator's machine-to-machine token, minted from the CLI. */
    private static RequestPostProcessor operator() {
        return jwt().jwt(token -> token.subject("m2m-client-id").claim("scope", TicketingScopes.OPS));
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject("some-customer").claim("scope", "aws.cognito.signin.user.admin"));
    }

    @Test
    void returns201WithCreatedAndSkippedCounts() throws Exception {
        when(seatSeedService.seedSeats("evt-1", List.of("A-1", "A-2", "A-3")))
                .thenReturn(new SeedResult(2, 1));

        mockMvc.perform(post("/admin/events/{eventId}/seats", "evt-1").with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A-1\",\"A-2\",\"A-3\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    void aCustomerCannotSeedSeats() throws Exception {
        mockMvc.perform(post("/admin/events/{eventId}/seats", "evt-1").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A-1\"]}"))
                .andExpect(status().isForbidden());

        verify(seatSeedService, never()).seedSeats(any(), any());
    }
}
