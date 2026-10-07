package com.ticketwaitingroom.checkoutpayment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to give money back for a captured payment.
 *
 * <p>Written before the gateway is called, for the same reason as {@link Payment}: the
 * insert claims the right to refund under {@code ux_refunds_one_live_per_payment}, and a
 * crash mid-call leaves a PENDING row that says a refund may have happened, rather than a
 * refund nobody has a record of.
 */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "amount_cents", nullable = false, updatable = false)
    private long amountCents;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "gateway_ref")
    private String gatewayRef;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private Refund(UUID orderId, Payment captured, String reason, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.paymentId = captured.getId();
        this.status = RefundStatus.PENDING;
        this.amountCents = captured.getAmountCents();
        this.currency = captured.getCurrency();
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
    }

    /**
     * Claim the right to refund a captured payment, for its full amount. The insert must
     * succeed before the gateway is called.
     */
    public static Refund pending(UUID orderId, Payment captured, String reason, String idempotencyKey, Instant now) {
        if (!captured.isCaptured()) {
            throw new IllegalStateException("Payment " + captured.getId() + " is " + captured.getStatus()
                    + "; only a captured payment can be refunded");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A refund must say why it was made");
        }
        return new Refund(orderId, captured, reason, idempotencyKey, now);
    }

    public boolean isPending() {
        return status == RefundStatus.PENDING;
    }

    public boolean isSucceeded() {
        return status == RefundStatus.SUCCEEDED;
    }

    /**
     * Record what the gateway said. Guarded on PENDING so a duplicated completion cannot
     * rewrite a settled refund.
     */
    public void complete(RefundResult result, Instant now) {
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Refund " + id + " is already " + status);
        }
        switch (result) {
            case RefundResult.Refunded refunded -> {
                this.status = RefundStatus.SUCCEEDED;
                this.gatewayRef = refunded.gatewayRef();
            }
            case RefundResult.Failed failed -> {
                this.status = RefundStatus.FAILED;
                this.failureReason = failed.reason();
            }
        }
        this.completedAt = now;
    }
}
