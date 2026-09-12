package com.ticketwaitingroom.ticketissuance.web;

import com.ticketwaitingroom.common.events.TicketIssuanceMessage;
import com.ticketwaitingroom.common.exceptions.NotYoursException;
import com.ticketwaitingroom.common.exceptions.TicketNotFoundException;
import com.ticketwaitingroom.ticketissuance.qr.TicketQrStore;
import com.ticketwaitingroom.ticketissuance.ticket.Ticket;
import com.ticketwaitingroom.ticketissuance.ticket.TicketIssuanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives requests through the real filter chain, which arrives from {@code security-starter}
 * with nothing to add — this service declares no path rules, so every endpoint needs a valid
 * token and nothing more. See {@code WaitingRoomControllerTest} for how the token is
 * supplied.
 */
@WebMvcTest(TicketController.class)
class TicketControllerTest {

    /** Shaped like a Cognito {@code sub}, rather than a friendly label. */
    private static final String CUSTOMER = "b4c1e0a2-1111-4000-8000-000000000009";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketIssuanceService issuanceService;

    @MockitoBean
    private TicketQrStore qrStore;

    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(CUSTOMER).claim("scope", "aws.cognito.signin.user.admin"));
    }

    @Test
    void listsTheCallersTicketsForAnOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        Ticket ticket = aTicket(orderId);
        when(issuanceService.findByOrder(orderId, CUSTOMER)).thenReturn(List.of(ticket));

        mockMvc.perform(get("/orders/{orderId}/tickets", orderId).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ticket.getId().toString()))
                .andExpect(jsonPath("$[0].seatId").value("A-1"))
                .andExpect(jsonPath("$[0].gatewayRef").value("gw-ref-1"));
    }

    @Test
    void qrIsReadyWithAPresignedUrl() throws Exception {
        Ticket ticket = aTicket(UUID.randomUUID());
        Instant expiry = Instant.parse("2026-08-29T12:15:00Z");
        when(issuanceService.findOwnedTicket(ticket.getId(), CUSTOMER)).thenReturn(ticket);
        when(qrStore.presign(ticket.getQrObjectKey()))
                .thenReturn(Optional.of(new TicketQrStore.PresignedQr("https://s3/qr.png?sig", expiry)));

        mockMvc.perform(get("/tickets/{ticketId}/qr", ticket.getId()).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.url").value("https://s3/qr.png?sig"));
    }

    @Test
    void qrIsPendingWhileTheImageIsStillBeingRendered() throws Exception {
        Ticket ticket = aTicket(UUID.randomUUID());
        when(issuanceService.findOwnedTicket(ticket.getId(), CUSTOMER)).thenReturn(ticket);
        when(qrStore.presign(any())).thenReturn(Optional.empty());

        // 200 PENDING, not 404: the ticket exists and is valid, only its rendering is late.
        mockMvc.perform(get("/tickets/{ticketId}/qr", ticket.getId()).with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void unknownTicketIs404() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(issuanceService.findOwnedTicket(ticketId, CUSTOMER))
                .thenThrow(new TicketNotFoundException("No ticket " + ticketId));

        mockMvc.perform(get("/tickets/{ticketId}/qr", ticketId).with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket not found"));
    }

    @Test
    void anotherCustomersQrIsRefusedWithoutEverMintingASignature() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(issuanceService.findOwnedTicket(ticketId, CUSTOMER))
                .thenThrow(new NotYoursException("No ticket " + ticketId));

        mockMvc.perform(get("/tickets/{ticketId}/qr", ticketId).with(customer()))
                // Identical to an unknown id, so the endpoint cannot be used to test which
                // ticket ids are real.
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Ticket not found"));

        // The point of the check is the credential, not the status code: a presigned URL is
        // a bearer token for the object, and one must never be created for a non-owner.
        verify(qrStore, never()).presign(any());
    }

    @Test
    void aQrRequestWithNoTokenIs401BeforeAnyCodeRuns() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(get("/tickets/{ticketId}/qr", ticketId))
                .andExpect(status().isUnauthorized());

        verify(issuanceService, never()).findOwnedTicket(any(), any());
        verify(qrStore, never()).presign(any());
    }

    private static Ticket aTicket(UUID orderId) {
        return Ticket.issue(new TicketIssuanceMessage(orderId.toString(), "evt-1", "A-1",
                CUSTOMER, "gw-ref-1", 12000L), Instant.parse("2026-08-29T12:00:00Z"));
    }
}
