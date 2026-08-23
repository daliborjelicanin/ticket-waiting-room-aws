package com.ticketwaitingroom.waitingroom.web;

import com.ticketwaitingroom.common.exceptions.UnknownQueueTokenException;
import com.ticketwaitingroom.waitingroom.queue.QueuePlace;
import com.ticketwaitingroom.waitingroom.queue.QueueStatus;
import com.ticketwaitingroom.waitingroom.queue.WaitingRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WaitingRoomControllerTest {

    private final WaitingRoomService waitingRoomService = mock(WaitingRoomService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new WaitingRoomController(waitingRoomService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void joinWithoutBodyReturns201WithAFreshToken() throws Exception {
        when(waitingRoomService.join("evt-1", null))
                .thenReturn(new QueuePlace("evt-1", "tok-abc", QueueStatus.WAITING, 7L, 42, null));

        mockMvc.perform(post("/events/{eventId}/queue", "evt-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok-abc"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(7))
                .andExpect(jsonPath("$.queueLength").value(42));
    }

    @Test
    void joinPassesAnExistingTokenThroughForIdempotentRejoin() throws Exception {
        when(waitingRoomService.join("evt-1", "tok-abc"))
                .thenReturn(new QueuePlace("evt-1", "tok-abc", QueueStatus.WAITING, 7L, 42, null));

        mockMvc.perform(post("/events/{eventId}/queue", "evt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok-abc\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(7));

        verify(waitingRoomService).join("evt-1", "tok-abc");
    }

    @Test
    void placeReturnsTheAdmissionPassOnceAdmitted() throws Exception {
        when(waitingRoomService.place("evt-1", "tok-abc"))
                .thenReturn(new QueuePlace("evt-1", "tok-abc", QueueStatus.ADMITTED, null, 0,
                        Instant.parse("2026-07-05T12:00:00Z")));

        mockMvc.perform(get("/events/{eventId}/queue/{token}", "evt-1", "tok-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.admissionExpiresAt").value("2026-07-05T12:00:00Z"));
    }

    @Test
    void placeReturns404ForAnUnknownToken() throws Exception {
        when(waitingRoomService.place("evt-1", "tok-abc"))
                .thenThrow(new UnknownQueueTokenException("Token tok-abc is not in the waiting room for event evt-1"));

        mockMvc.perform(get("/events/{eventId}/queue/{token}", "evt-1", "tok-abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Unknown queue token"));
    }

    @Test
    void admitReturnsTheAdmittedTokensInOrder() throws Exception {
        when(waitingRoomService.admitNext("evt-1", 3))
                .thenReturn(new WaitingRoomService.Admission(
                        List.of("tok-a", "tok-b"), Instant.parse("2026-07-05T12:00:00Z")));

        mockMvc.perform(post("/events/{eventId}/queue/admit", "evt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokens[0]").value("tok-a"))
                .andExpect(jsonPath("$.tokens[1]").value("tok-b"))
                .andExpect(jsonPath("$.admitted").value(2));
    }

    @Test
    void admitReturns400ForAnOutOfRangeCount() throws Exception {
        when(waitingRoomService.admitNext("evt-1", 0))
                .thenThrow(new IllegalArgumentException("Admit count must be between 1 and 500, was 0"));

        mockMvc.perform(post("/events/{eventId}/queue/admit", "evt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }
}
