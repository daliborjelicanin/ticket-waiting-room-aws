package com.ticketwaitingroom.checkoutpayment.checkout;

import com.ticketwaitingroom.checkoutpayment.config.CheckoutProperties;
import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderRepository;
import com.ticketwaitingroom.checkoutpayment.order.OrderSeat;
import com.ticketwaitingroom.checkoutpayment.payment.AttemptClaim;
import com.ticketwaitingroom.checkoutpayment.payment.ChargeOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayCharge;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayResult;
import com.ticketwaitingroom.checkoutpayment.payment.Payment;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentGateway;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentService;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The checkout step of the saga: create an order for seats already held, then charge it.
 *
 * <p>{@link #charge} is intentionally <em>not</em> transactional. It spans a call to an
 * external gateway, and holding a database transaction across that would trade a payment
 * provider's latency for this service's connection pool. The transactional units live in
 * {@link PaymentService}, one on each side of the network call, and are invoked through a
 * separate bean so Spring's proxy actually applies.
 */
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final CheckoutProperties properties;
    private final Clock clock;

    /**
     * Create an order for seats the inventory service has already held.
     *
     * <p>Idempotent on {@code idempotencyKey}: a retried create returns the original order
     * rather than a second one the customer could be charged for separately.
     */
    @Transactional
    public Order createOrder(String eventId, String customerRef, long amountCents,
                             String idempotencyKey, List<OrderSeat> seats) {
        return orderRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> orderRepository.saveAndFlush(Order.pending(
                        eventId, customerRef, amountCents, properties.currency(),
                        idempotencyKey, seats, clock.instant())));
    }

    @Transactional(readOnly = true)
    public Order findOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("No order " + orderId));
    }

    /**
     * Charge an order: claim the attempt, call the gateway, record what it said.
     *
     * <p>The claim is committed before the gateway is called, so a crash mid-charge leaves
     * a PENDING row rather than a charge nobody has any record of.
     */
    public ChargeOutcome charge(UUID orderId, String idempotencyKey, String paymentToken) {
        AttemptClaim claim;
        try {
            claim = paymentService.beginAttempt(orderId, idempotencyKey, paymentToken, clock.instant());
        } catch (DataIntegrityViolationException conflict) {
            // A rival attempt won the insert race. The database already decided which of
            // the two constraints was hit; this just reports what that means.
            return paymentService.resolveConflict(orderId, idempotencyKey, conflict);
        }

        return switch (claim) {
            // Already decided: return the original answer without touching the gateway.
            case AttemptClaim.AlreadySettled settled -> settled.outcome();
            case AttemptClaim.Claimed claimed -> {
                Payment attempt = claimed.payment();
                GatewayResult result = paymentGateway.charge(new GatewayCharge(
                        idempotencyKey, paymentToken, attempt.getAmountCents(), attempt.getCurrency()));
                yield paymentService.completeAttempt(attempt.getId(), result, clock.instant());
            }
        };
    }

    /**
     * Abandon a PENDING order. The seat holds it covers become releasable, which is the
     * same compensating path a payment failure takes.
     */
    @Transactional
    public Order cancel(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("No order " + orderId));
        order.markCancelled(clock.instant());
        return order;
    }
}
