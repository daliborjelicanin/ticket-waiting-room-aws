package com.ticketwaitingroom.checkoutpayment.payment;

import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderRepository;
import com.ticketwaitingroom.common.exceptions.ChargeInProgressException;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import com.ticketwaitingroom.common.exceptions.OrderNotPayableException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The transactional half of taking a payment, deliberately split into two units either
 * side of the gateway call.
 *
 * <p>It is split because a network call must not happen inside a database transaction: it
 * would pin a connection for the duration of someone else's outage, and a slow gateway
 * would turn into a connection-pool exhaustion incident. So the attempt is claimed and
 * committed first, the gateway is called with nothing held, and the outcome is written in
 * a second transaction.
 *
 * <p>What makes that safe is the database, not this class. The insert in
 * {@link #beginAttempt} has to pass {@code ux_payments_one_live_per_order}, so only one
 * caller can hold a live attempt on an order at a time however many race in.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String IDEMPOTENCY_CONSTRAINT = "uq_payments_idempotency_key";

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    /**
     * Claim the exclusive right to charge this order, committing a PENDING payment row.
     *
     * <p>The idempotency key is checked <em>before</em> the order's status, and the order
     * matters: replaying a settled attempt has to return the original answer even though
     * the order is by then PAID or FAILED. Checking payability first would turn every
     * retry of a successful charge into an error, which is precisely the case idempotency
     * keys exist to handle.
     *
     * @throws ChargeInProgressException     if this key names an attempt still in flight
     * @throws OrderNotPayableException      if the order is settled and this is a new key
     * @throws DataIntegrityViolationException if a rival attempt won the race — the caller
     *                                       resolves it via {@link #resolveConflict}
     */
    @Transactional
    public AttemptClaim beginAttempt(UUID orderId, String idempotencyKey, String paymentToken, Instant now) {
        Payment settled = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (settled != null) {
            if (settled.isPending()) {
                throw new ChargeInProgressException(
                        "Payment attempt " + idempotencyKey + " is still in flight");
            }
            return new AttemptClaim.AlreadySettled(outcome(settled));
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("No order " + orderId));
        if (!order.isPending()) {
            throw new OrderNotPayableException(
                    "Order " + orderId + " is " + order.getStatus() + " and cannot be charged");
        }
        Payment attempt = Payment.pending(order.getId(), order.getAmountCents(), order.getCurrency(),
                paymentToken, idempotencyKey, now);
        // saveAndFlush, not save: the constraint must be enforced here and now, while it can
        // still be turned into an answer, rather than at some later commit inside the caller.
        return new AttemptClaim.Claimed(paymentRepository.saveAndFlush(attempt));
    }

    /**
     * Work out what a failed {@link #beginAttempt} insert actually meant.
     *
     * <p>Runs in its own transaction because the one that hit the constraint is already
     * marked rollback-only — nothing further can be read on it.
     *
     * @return the original attempt, if this was a replay of a completed one
     * @throws ChargeInProgressException if a different attempt currently holds the order
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public ChargeOutcome resolveConflict(UUID orderId, String idempotencyKey,
                                         DataIntegrityViolationException failure) {
        if (isIdempotencyReplay(failure)) {
            Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> failure);
            if (existing.isPending()) {
                // Same key, still in flight elsewhere. Returning "pending" would invite the
                // caller to treat an unknown outcome as a final one.
                throw new ChargeInProgressException(
                        "Payment attempt " + idempotencyKey + " is still in flight");
            }
            return outcome(existing);
        }
        throw new ChargeInProgressException(
                "Another payment attempt is already live for order " + orderId);
    }

    /**
     * Record the gateway's answer and move the order with it, in one transaction. This is
     * the atomicity that matters: a captured payment and a PAID order commit together, so
     * there is no window in which money has moved and the order does not know.
     */
    @Transactional
    public ChargeOutcome completeAttempt(UUID paymentId, GatewayResult result, Instant now) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("No payment " + paymentId));
        payment.complete(result, now);

        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("No order " + payment.getOrderId()));
        if (payment.isCaptured()) {
            order.markPaid(now);
        } else {
            // A decline or a gateway failure both end the order, which is what triggers the
            // saga's compensating release of the seat holds.
            order.markFailed(now);
        }
        return new ChargeOutcome(order, payment);
    }

    private ChargeOutcome outcome(Payment payment) {
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("No order " + payment.getOrderId()));
        return new ChargeOutcome(order, payment);
    }

    private static boolean isIdempotencyReplay(DataIntegrityViolationException failure) {
        return failure.getCause() instanceof ConstraintViolationException violation
                && IDEMPOTENCY_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
    }
}
