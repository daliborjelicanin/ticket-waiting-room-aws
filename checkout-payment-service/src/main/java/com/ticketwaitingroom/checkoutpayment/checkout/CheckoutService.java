package com.ticketwaitingroom.checkoutpayment.checkout;

import com.ticketwaitingroom.checkoutpayment.config.CheckoutProperties;
import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderRepository;
import com.ticketwaitingroom.checkoutpayment.order.OrderSeat;
import com.ticketwaitingroom.checkoutpayment.payment.AttemptClaim;
import com.ticketwaitingroom.checkoutpayment.payment.ChargeOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayCharge;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayRefund;
import com.ticketwaitingroom.checkoutpayment.payment.GatewayResult;
import com.ticketwaitingroom.checkoutpayment.payment.Payment;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentGateway;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentService;
import com.ticketwaitingroom.checkoutpayment.payment.Refund;
import com.ticketwaitingroom.checkoutpayment.payment.RefundClaim;
import com.ticketwaitingroom.checkoutpayment.payment.RefundOutcome;
import com.ticketwaitingroom.checkoutpayment.payment.RefundResult;
import com.ticketwaitingroom.checkoutpayment.payment.RefundService;
import com.ticketwaitingroom.common.exceptions.ChargeInProgressException;
import com.ticketwaitingroom.common.exceptions.NotYoursException;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import com.ticketwaitingroom.common.exceptions.OrderNotPayableException;
import com.ticketwaitingroom.common.exceptions.OrderNotRefundableException;
import com.ticketwaitingroom.common.exceptions.RefundInProgressException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * The checkout steps of the saga: create an order for seats already held, charge it, and,
 * if the seat cannot then be confirmed, refund it.
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
    private final RefundService refundService;
    private final PaymentGateway paymentGateway;
    private final CheckoutProperties properties;
    private final CheckoutMetrics metrics;
    private final Clock clock;

    /**
     * Create an order for seats the inventory service has already held.
     *
     * <p>Idempotent on {@code idempotencyKey}: a retried create returns the original order
     * rather than a second one the customer could be charged for separately.
     */
    @Transactional
    public Order createOrder(UUID orderId, String eventId, String customerRef, long amountCents,
                             String idempotencyKey, List<OrderSeat> seats) {
        Order existing = orderRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            metrics.orderCreated(false);
            return existing;
        }
        Order order = orderRepository.saveAndFlush(Order.pending(
                orderId, eventId, customerRef, amountCents, properties.currency(),
                idempotencyKey, seats, clock.instant()));
        metrics.orderCreated(true);
        return order;
    }

    /**
     * One customer's order.
     *
     * <p>Not reachable by the saga, which only ever creates and charges — so unlike those
     * two, this is a fan-door call and the caller's {@code sub} is checked against the row.
     * An order belonging to somebody else is reported as missing rather than as forbidden,
     * since a 403 would confirm the id is real and turn this into an oracle for walking
     * the id space.
     */
    @Transactional(readOnly = true)
    public Order findOrder(UUID orderId, String customerRef) {
        return requireOwned(orderId, customerRef);
    }

    /**
     * Charge an order: claim the attempt, call the gateway, record what it said.
     *
     * <p>The claim is committed before the gateway is called, so a crash mid-charge leaves
     * a PENDING row rather than a charge nobody has any record of.
     */
    public ChargeOutcome charge(UUID orderId, String idempotencyKey, String paymentToken) {
        try {
            return chargeCounted(orderId, idempotencyKey, paymentToken);
        } catch (ChargeInProgressException inFlight) {
            metrics.charged("in_flight");
            throw inFlight;
        } catch (OrderNotPayableException settled) {
            metrics.charged("not_payable");
            throw settled;
        }
    }

    private ChargeOutcome chargeCounted(UUID orderId, String idempotencyKey, String paymentToken) {
        AttemptClaim claim;
        try {
            claim = paymentService.beginAttempt(orderId, idempotencyKey, paymentToken, clock.instant());
        } catch (DataIntegrityViolationException conflict) {
            // A rival attempt won the insert race. The database already decided which of
            // the two constraints was hit; this just reports what that means.
            ChargeOutcome replayed = paymentService.resolveConflict(orderId, idempotencyKey, conflict);
            metrics.charged("replayed");
            return replayed;
        }

        return switch (claim) {
            // Already decided: return the original answer without touching the gateway.
            case AttemptClaim.AlreadySettled settled -> {
                metrics.charged("replayed");
                yield settled.outcome();
            }
            case AttemptClaim.Claimed claimed -> {
                Payment attempt = claimed.payment();
                GatewayResult result = metrics.timeGateway(() -> paymentGateway.charge(new GatewayCharge(
                        idempotencyKey, paymentToken, attempt.getAmountCents(), attempt.getCurrency())));
                ChargeOutcome outcome = paymentService.completeAttempt(attempt.getId(), result, clock.instant());
                metrics.charged(CheckoutMetrics.outcomeOf(result));
                yield outcome;
            }
        };
    }

    /**
     * Refund a paid order: claim the refund, call the gateway, record what it said.
     *
     * <p>The saga's compensation after money has moved. It calls this only when the seat
     * inventory has <em>definitively</em> refused the confirm (a 404 from an idempotent
     * confirm), never on a timeout, because a timeout cannot tell a sold seat from an
     * unsold one. The structure is the charge's, with the same claim-before-call ordering
     * and the same key-before-state check, so a replay of a refund that has already been
     * decided returns its original answer.
     */
    public RefundOutcome refund(UUID orderId, String idempotencyKey, String reason) {
        try {
            return refundCounted(orderId, idempotencyKey, reason);
        } catch (RefundInProgressException inFlight) {
            metrics.refunded("in_flight");
            throw inFlight;
        } catch (OrderNotRefundableException notPaid) {
            metrics.refunded("not_refundable");
            throw notPaid;
        }
    }

    private RefundOutcome refundCounted(UUID orderId, String idempotencyKey, String reason) {
        RefundClaim claim;
        try {
            claim = refundService.beginRefund(orderId, idempotencyKey, reason, clock.instant());
        } catch (DataIntegrityViolationException conflict) {
            RefundOutcome replayed = refundService.resolveConflict(orderId, idempotencyKey, conflict);
            metrics.refunded("replayed");
            return replayed;
        }

        return switch (claim) {
            case RefundClaim.AlreadySettled settled -> {
                metrics.refunded("replayed");
                yield settled.outcome();
            }
            case RefundClaim.Claimed claimed -> {
                Refund attempt = claimed.refund();
                RefundResult result = paymentGateway.refund(new GatewayRefund(
                        idempotencyKey, claimed.chargeRef(), attempt.getAmountCents(), attempt.getCurrency()));
                RefundOutcome outcome = refundService.completeRefund(attempt.getId(), result, clock.instant());
                metrics.refunded(CheckoutMetrics.outcomeOf(result));
                yield outcome;
            }
        };
    }

    /**
     * Abandon a PENDING order. The seat holds it covers become releasable, which is the
     * same compensating path a payment failure takes.
     */
    @Transactional
    public Order cancel(UUID orderId, String customerRef) {
        Order order = requireOwned(orderId, customerRef);
        order.markCancelled(clock.instant());
        return order;
    }

    private Order requireOwned(UUID orderId, String customerRef) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("No order " + orderId));
        if (!customerRef.equals(order.getCustomerRef())) {
            throw new NotYoursException("No order " + orderId);
        }
        return order;
    }
}
