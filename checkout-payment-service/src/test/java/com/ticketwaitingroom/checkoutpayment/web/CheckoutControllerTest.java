package com.ticketwaitingroom.checkoutpayment.web;

import com.ticketwaitingroom.checkoutpayment.checkout.CheckoutService;
import com.ticketwaitingroom.checkoutpayment.config.SecurityConfig;
import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderSeat;
import com.ticketwaitingroom.checkoutpayment.payment.ChargeOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayResult;
import com.ticketwaitingroom.checkoutpayment.payment.Payment;
import com.ticketwaitingroom.checkoutpayment.payment.Refund;
import com.ticketwaitingroom.checkoutpayment.payment.RefundOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.RefundResult;
import com.ticketwaitingroom.common.exceptions.ChargeInProgressException;
import com.ticketwaitingroom.common.exceptions.NotYoursException;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import com.ticketwaitingroom.common.exceptions.OrderNotPayableException;
import com.ticketwaitingroom.common.exceptions.OrderNotRefundableException;
import com.ticketwaitingroom.common.identity.TicketingScopes;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives requests through the real {@link SecurityConfig}; see
 * {@code WaitingRoomControllerTest} for how the token is supplied.
 */
@WebMvcTest(CheckoutController.class)
@Import(SecurityConfig.class)
class CheckoutControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");
    private static final UUID ORDER_ID = UUID.fromString("6f1c0e0a-0000-4000-8000-000000000001");
    private static final String CUSTOMER = "b4c1e0a2-1111-4000-8000-000000000009";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutService checkoutService;

    /** A customer's access token: Cognito's own sign-in scope, none of ours. */
    private static RequestPostProcessor customer() {
        return jwt().jwt(token -> token.subject(CUSTOMER).claim("scope", "aws.cognito.signin.user.admin"));
    }

    /** The saga's machine-to-machine token. */
    private static RequestPostProcessor saga() {
        return jwt().jwt(token -> token.subject("m2m-client-id").claim("scope", TicketingScopes.OPS));
    }

    @Test
    void createOrderReturns201WithTheOrder() throws Exception {
        when(checkoutService.createOrder(eq(ORDER_ID), eq("evt-1"), eq(CUSTOMER), anyLong(), eq("key-1"), any()))
                .thenReturn(anOrder());

        // The saga names the customer in the body and is trusted to, because it holds the
        // ops scope. The order id is chosen by the client up front: the saga is started
        // through API Gateway, which answers with an execution arn, so the client must
        // already know what to poll.
        mockMvc.perform(post("/orders").with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","eventId":"evt-1","customerRef":"%s","amountCents":4500,
                                 "idempotencyKey":"key-1",
                                 "seats":[{"seatId":"A-1","holdId":"hold-1"}]}""".formatted(ORDER_ID, CUSTOMER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountCents").value(4500))
                .andExpect(jsonPath("$.seats[0].seatId").value("A-1"))
                .andExpect(jsonPath("$.seats[0].holdId").value("hold-1"));
    }

    @Test
    void chargeReturnsCapturedOutcome() throws Exception {
        Order order = anOrder();
        order.markPaid(NOW);
        Payment payment = aPayment(order.getId());
        payment.complete(new GatewayResult.Approved("mock_1"), NOW);
        when(checkoutService.charge(eq(order.getId()), eq("pay-1"), eq("tok_ok")))
                .thenReturn(new ChargeOutcome(order, payment));

        mockMvc.perform(post("/orders/{orderId}/payment", order.getId()).with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"pay-1\",\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("PAID"))
                .andExpect(jsonPath("$.paymentStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.gatewayRef").value("mock_1"));
    }

    @Test
    void declineIs200WithTheFailureReasonNotAnError() throws Exception {
        Order order = anOrder();
        order.markFailed(NOW);
        Payment payment = aPayment(order.getId());
        payment.complete(new GatewayResult.Declined("card_declined"), NOW);
        when(checkoutService.charge(any(), anyString(), anyString()))
                .thenReturn(new ChargeOutcome(order, payment));

        // A decline is an answer, not a fault: the saga needs the body to decide to compensate.
        mockMvc.perform(post("/orders/{orderId}/payment", order.getId()).with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"pay-1\",\"paymentToken\":\"tok_decline\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("FAILED"))
                .andExpect(jsonPath("$.paymentStatus").value("DECLINED"))
                .andExpect(jsonPath("$.failureReason").value("card_declined"));
    }

    @Test
    void unknownOrderIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.findOrder(id, CUSTOMER)).thenThrow(new OrderNotFoundException("No order " + id));

        mockMvc.perform(get("/orders/{orderId}", id).with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void anotherCustomersOrderIsAlso404AndLooksIdentical() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.findOrder(id, CUSTOMER)).thenThrow(new NotYoursException("No order " + id));

        mockMvc.perform(get("/orders/{orderId}", id).with(customer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void readingAnOrderWithNoTokenIs401() throws Exception {
        mockMvc.perform(get("/orders/{orderId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        verify(checkoutService, never()).findOrder(any(), any());
    }

    @Test
    void aCustomerCannotCreateOrChargeAnOrderDirectly() throws Exception {
        // The saga endpoints move money on somebody's behalf, so a customer's own token —
        // valid, from the same pool — must not open them. Only the ops scope does.
        mockMvc.perform(post("/orders").with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders/{orderId}/payment", UUID.randomUUID()).with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/orders/{orderId}/refund", UUID.randomUUID()).with(customer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(checkoutService, never()).createOrder(any(), any(), any(), anyLong(), any(), any());
        verify(checkoutService, never()).charge(any(), any(), any());
        verify(checkoutService, never()).refund(any(), any(), any());
    }

    @Test
    void refundReturnsTheRefundedOrder() throws Exception {
        Order order = anOrder();
        Payment payment = aPayment(order.getId());
        payment.complete(new GatewayResult.Approved("mock_ref"), NOW);
        order.markPaid(NOW);
        Refund refund = Refund.pending(order.getId(), payment, "seat_not_confirmed", "pay-1:refund", NOW);
        refund.complete(new RefundResult.Refunded("mock_refund_1"), NOW);
        order.markRefunded(NOW);
        when(checkoutService.refund(order.getId(), "pay-1:refund", "seat_not_confirmed"))
                .thenReturn(new RefundOutcome(order, refund));

        mockMvc.perform(post("/orders/{orderId}/refund", order.getId()).with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"pay-1:refund\",\"reason\":\"seat_not_confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.gatewayRef").value("mock_refund_1"));
    }

    @Test
    void refundingAnUnpaidOrderIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.refund(any(), anyString(), anyString()))
                .thenThrow(new OrderNotRefundableException("Order " + id + " is PENDING and has nothing to refund"));

        mockMvc.perform(post("/orders/{orderId}/refund", id).with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"r-1\",\"reason\":\"seat_not_confirmed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order not refundable"));
    }

    @Test
    void chargingASettledOrderIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.charge(any(), anyString(), anyString()))
                .thenThrow(new OrderNotPayableException("Order " + id + " is PAID and cannot be charged"));

        mockMvc.perform(post("/orders/{orderId}/payment", id).with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"pay-2\",\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Order not payable"));
    }

    @Test
    void aChargeStillInFlightIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.charge(any(), anyString(), anyString()))
                .thenThrow(new ChargeInProgressException("Payment attempt pay-1 is still in flight"));

        mockMvc.perform(post("/orders/{orderId}/payment", id).with(saga())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"pay-1\",\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Charge already in progress"));
    }

    private static Order anOrder() {
        return Order.pending(ORDER_ID, "evt-1", "cust-1", 4500, "EUR", "key-1",
                List.of(new OrderSeat("A-1", "hold-1")), NOW);
    }

    private static Payment aPayment(UUID orderId) {
        return Payment.pending(orderId, 4500, "EUR", "tok_ok", "pay-1", NOW);
    }
}
