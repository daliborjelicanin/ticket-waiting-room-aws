package com.ticketwaitingroom.checkoutpayment.web;

import com.ticketwaitingroom.checkoutpayment.checkout.CheckoutService;
import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderSeat;
import com.ticketwaitingroom.checkoutpayment.order.OrderStatus;
import com.ticketwaitingroom.checkoutpayment.payment.ChargeOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayResult;
import com.ticketwaitingroom.checkoutpayment.payment.Payment;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentStatus;
import com.ticketwaitingroom.common.exceptions.ChargeInProgressException;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import com.ticketwaitingroom.common.exceptions.OrderNotPayableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CheckoutControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");

    private final CheckoutService checkoutService = mock(CheckoutService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standaloneSetup(new CheckoutController(checkoutService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createOrderReturns201WithTheOrder() throws Exception {
        when(checkoutService.createOrder(eq("evt-1"), eq("cust-1"), anyLong(), eq("key-1"), any()))
                .thenReturn(anOrder());

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-1","customerRef":"cust-1","amountCents":4500,
                                 "idempotencyKey":"key-1",
                                 "seats":[{"seatId":"A-1","holdId":"hold-1"}]}"""))
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

        mockMvc.perform(post("/orders/{orderId}/payment", order.getId())
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
        mockMvc.perform(post("/orders/{orderId}/payment", order.getId())
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
        when(checkoutService.findOrder(id)).thenThrow(new OrderNotFoundException("No order " + id));

        mockMvc.perform(get("/orders/{orderId}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Order not found"));
    }

    @Test
    void chargingASettledOrderIs409() throws Exception {
        UUID id = UUID.randomUUID();
        when(checkoutService.charge(any(), anyString(), anyString()))
                .thenThrow(new OrderNotPayableException("Order " + id + " is PAID and cannot be charged"));

        mockMvc.perform(post("/orders/{orderId}/payment", id)
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

        mockMvc.perform(post("/orders/{orderId}/payment", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"pay-1\",\"paymentToken\":\"tok_ok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Charge already in progress"));
    }

    private static Order anOrder() {
        return Order.pending("evt-1", "cust-1", 4500, "EUR", "key-1",
                List.of(new OrderSeat("A-1", "hold-1")), NOW);
    }

    private static Payment aPayment(UUID orderId) {
        return Payment.pending(orderId, 4500, "EUR", "tok_ok", "pay-1", NOW);
    }
}
