package com.ticketwaitingroom.checkoutpayment.payment;

import com.ticketwaitingroom.checkoutpayment.order.Order;
import com.ticketwaitingroom.checkoutpayment.order.OrderRepository;
import com.ticketwaitingroom.common.exceptions.OrderNotFoundException;
import com.ticketwaitingroom.common.exceptions.OrderNotRefundableException;
import com.ticketwaitingroom.common.exceptions.RefundInProgressException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The transactional half of giving money back, split either side of the gateway call
 * exactly as {@link PaymentService} splits a charge, and for the same reason: no network
 * call inside a database transaction.
 *
 * <p>The guarantee that a payment is refunded at most once is
 * {@code ux_refunds_one_live_per_payment}, not anything in this class.
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String IDEMPOTENCY_CONSTRAINT = "uq_refunds_idempotency_key";

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    /**
     * Claim the exclusive right to refund this order's capture, committing a PENDING refund.
     *
     * <p>The idempotency key is checked before the order's status, as for a charge: a replay
     * of a completed refund finds the order REFUNDED, and has to get the original answer
     * rather than "not refundable".
     *
     * @throws RefundInProgressException       if this key names a refund still in flight
     * @throws OrderNotRefundableException     if the order is not PAID and this is a new key
     * @throws DataIntegrityViolationException if a rival refund won the race — the caller
     *                                         resolves it via {@link #resolveConflict}
     */
    @Transactional
    public RefundClaim beginRefund(UUID orderId, String idempotencyKey, String reason, Instant now) {
        Refund settled = refundRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (settled != null) {
            if (settled.isPending()) {
                throw new RefundInProgressException("Refund " + idempotencyKey + " is still in flight");
            }
            return new RefundClaim.AlreadySettled(outcome(settled));
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("No order " + orderId));
        if (!order.isPaid()) {
            throw new OrderNotRefundableException(
                    "Order " + orderId + " is " + order.getStatus() + " and has nothing to refund");
        }
        Payment captured = paymentRepository.findByOrderIdAndStatus(orderId, PaymentStatus.CAPTURED)
                .orElseThrow(() -> new IllegalStateException(
                        "Order " + orderId + " is PAID with no captured payment"));
        Refund attempt = Refund.pending(order.getId(), captured, reason, idempotencyKey, now);
        // saveAndFlush, so the constraint is enforced here, where it can still become an answer.
        return new RefundClaim.Claimed(refundRepository.saveAndFlush(attempt), captured.getGatewayRef());
    }

    /**
     * Work out what a failed {@link #beginRefund} insert meant, in a fresh transaction
     * because the one that hit the constraint is rollback-only.
     *
     * @return the original refund, if this was a replay of a completed one
     * @throws RefundInProgressException if a different refund currently holds the payment
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RefundOutcome resolveConflict(UUID orderId, String idempotencyKey,
                                         DataIntegrityViolationException failure) {
        if (isIdempotencyReplay(failure)) {
            Refund existing = refundRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> failure);
            if (existing.isPending()) {
                throw new RefundInProgressException("Refund " + idempotencyKey + " is still in flight");
            }
            return outcome(existing);
        }
        throw new RefundInProgressException("Another refund is already live for order " + orderId);
    }

    /**
     * Record the gateway's answer and, if the money went back, move the order to REFUNDED in
     * the same transaction. A failed refund leaves the order PAID: the money is still taken,
     * and saying otherwise would hide the one case a human has to act on.
     */
    @Transactional
    public RefundOutcome completeRefund(UUID refundId, RefundResult result, Instant now) {
        Refund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalStateException("No refund " + refundId));
        refund.complete(result, now);

        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("No order " + refund.getOrderId()));
        if (refund.isSucceeded()) {
            order.markRefunded(now);
        }
        return new RefundOutcome(order, refund);
    }

    private RefundOutcome outcome(Refund refund) {
        Order order = orderRepository.findById(refund.getOrderId())
                .orElseThrow(() -> new OrderNotFoundException("No order " + refund.getOrderId()));
        return new RefundOutcome(order, refund);
    }

    private static boolean isIdempotencyReplay(DataIntegrityViolationException failure) {
        return failure.getCause() instanceof ConstraintViolationException violation
                && IDEMPOTENCY_CONSTRAINT.equalsIgnoreCase(violation.getConstraintName());
    }
}
