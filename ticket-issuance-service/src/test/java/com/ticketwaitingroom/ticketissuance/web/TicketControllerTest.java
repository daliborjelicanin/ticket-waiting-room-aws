package com.ticketwaitingroom.ticketissuance.web;

import com.ticketwaitingroom.common.exceptions.TicketNotFoundException;
import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import com.ticketwaitingroom.ticketissuance.qr.TicketQrStore;
import com.ticketwaitingroom.ticketissuance.ticket.Ticket;
import com.ticketwaitingroom.ticketissuance.ticket.TicketIssuanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TicketControllerTest {

    private final TicketIssuanceService issuanceService = mock(TicketIssuanceService.class);
    private final TicketQrStore qrStore = mock(TicketQrStore.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new TicketController(issuanceService, qrStore))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void listsTicketsForAnOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        Ticket ticket = aTicket(orderId);
        when(issuanceService.findByOrder(orderId)).thenReturn(List.of(ticket));

        mockMvc.perform(get("/orders/{orderId}/tickets", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ticket.getId().toString()))
                .andExpect(jsonPath("$[0].seatId").value("A-1"))
                .andExpect(jsonPath("$[0].gatewayRef").value("gw-ref-1"));
    }

    @Test
    void qrIsReadyWithAPresignedUrl() throws Exception {
        Ticket ticket = aTicket(UUID.randomUUID());
        Instant expiry = Instant.parse("2026-08-29T12:15:00Z");
        when(issuanceService.findTicket(ticket.getId())).thenReturn(ticket);
        when(qrStore.presign(ticket.getQrObjectKey()))
                .thenReturn(Optional.of(new TicketQrStore.PresignedQr("https://s3/qr.png?sig", expiry)));

        mockMvc.perform(get("/tickets/{ticketId}/qr", ticket.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.url").value("https://s3/qr.png?sig"));
    }

    @Test
    void qrIsPendingWhileTheImageIsStillBeingRendered() throws Exception {
        Ticket ticket = aTicket(UUID.randomUUID());
        when(issuanceService.findTicket(ticket.getId())).thenReturn(ticket);
        when(qrStore.presign(any())).thenReturn(Optional.empty());

        // 200 PENDING, not 404: the ticket exists and is valid, only its rendering is late.
        mockMvc.perform(get("/tickets/{ticketId}/qr", ticket.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void unknownTicketIs404() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(issuanceService.findTicket(ticketId)).thenThrow(new TicketNotFoundException("No ticket " + ticketId));

        mockMvc.perform(get("/tickets/{ticketId}/qr", ticketId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket not found"));
    }

    private static Ticket aTicket(UUID orderId) {
        return Ticket.issue(new TicketIssuanceMessage(orderId.toString(), "evt-1", "A-1",
                "cust-1", "gw-ref-1", 12000L), Instant.parse("2026-08-29T12:00:00Z"));
    }
}
