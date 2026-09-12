package com.ticketwaitingroom.checkoutpayment.web;

import com.ticketwaitingroom.checkoutpayment.checkout.CheckoutService;
import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderSeat;
import com.ticketwaitingroom.checkoutpayment.order.OrderStatus;
import com.ticketwaitingroom.checkoutpayment.payment.ChargeOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * HTTP surface for checkout, split across the two doors.
 *
 * <p>{@code POST /orders} and {@code POST /orders/{id}/payment} are <strong>saga</strong>
 * endpoints: {@code SecurityConfig} admits only a token carrying the ops scope, which
 * Step Functions presents. They take a caller-supplied idempotency key, because the saga
 * retries them and a retry must never become a second order or a second charge. The
 * customer's identity arrives in the body: the saga is trusted to name the customer
 * because its input was built by API Gateway from that customer's own verified token.
 *
 * <p>{@code GET /orders/{id}} and {@code POST /orders/{id}/cancel} are
 * <strong>fan-door</strong> endpoints, scoped to the {@code sub} of whatever JWT the
 * customer presented.
 */
@RestController
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@RequestBody CreateOrderRequest request) {
        Order order = checkoutService.createOrder(
                request.orderId(),
                request.eventId(),
                request.customerRef(),
                request.amountCents(),
                request.idempotencyKey(),
                request.seats().stream().map(s -> new OrderSeat(s.seatId(), s.holdId())).toList());
        return OrderResponse.of(order);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse getOrder(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt caller) {
        return OrderResponse.of(checkoutService.findOrder(orderId, caller.getSubject()));
    }

    @PostMapping("/orders/{orderId}/payment")
    public ChargeResponse charge(@PathVariable UUID orderId, @RequestBody ChargeRequest request) {
        ChargeOutcome outcome = checkoutService.charge(orderId, request.idempotencyKey(), request.paymentToken());
        return new ChargeResponse(
                outcome.order().getId(),
                outcome.order().getStatus(),
                outcome.payment().getStatus(),
                outcome.payment().getGatewayRef(),
                outcome.payment().getFailureReason());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt caller) {
        return OrderResponse.of(checkoutService.cancel(orderId, caller.getSubject()));
    }

    public record SeatRef(String seatId, String holdId) { }

    public record CreateOrderRequest(UUID orderId, String eventId, String customerRef, long amountCents,
                                     String idempotencyKey, List<SeatRef> seats) { }

    public record ChargeRequest(String idempotencyKey, String paymentToken) { }

    public record ChargeResponse(UUID orderId, OrderStatus orderStatus, PaymentStatus paymentStatus,
                                 String gatewayRef, String failureReason) { }

    public record OrderResponse(UUID id, String eventId, String customerRef, OrderStatus status,
                                long amountCents, String currency, List<SeatRef> seats) {

        static OrderResponse of(Order order) {
            return new OrderResponse(order.getId(), order.getEventId(), order.getCustomerRef(),
                    order.getStatus(), order.getAmountCents(), order.getCurrency(),
                    order.seats().stream().map(s -> new SeatRef(s.getSeatId(), s.getHoldId())).toList());
        }
    }
}
