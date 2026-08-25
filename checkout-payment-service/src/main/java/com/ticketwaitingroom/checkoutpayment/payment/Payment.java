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
 * One attempt to move money for an order.
 *
 * <p>A row is written <em>before</em> the gateway is called, not after. That ordering is
 * the whole point: the insert is what claims the right to charge, under the partial unique
 * index {@code ux_payments_one_live_per_order}. If the process dies mid-call the row
 * survives as PENDING, which is a visible, recoverable state — whereas writing only on
 * success would leave a charge that happened with nothing in the database to say so.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_token", nullable = false, updatable = false)
    private String paymentToken;

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

    private Payment(UUID orderId, long amountCents, String currency, String paymentToken,
                    String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.status = PaymentStatus.PENDING;
        this.amountCents = amountCents;
        this.currency = currency;
        this.paymentToken = paymentToken;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
    }

    /** Claim the right to charge this order. Insert must succeed before the gateway call. */
    public static Payment pending(UUID orderId, long amountCents, String currency, String paymentToken,
                                  String idempotencyKey, Instant now) {
        return new Payment(orderId, amountCents, currency, paymentToken, idempotencyKey, now);
    }

    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }

    public boolean isCaptured() {
        return status == PaymentStatus.CAPTURED;
    }

    /**
     * Record what the gateway said. Guarded on PENDING so a duplicated completion cannot
     * rewrite a settled attempt — which would be how a captured payment silently becomes
     * a declined one.
     */
    public void complete(GatewayResult result, Instant now) {
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment " + id + " is already " + status);
        }
        switch (result) {
            case GatewayResult.Approved approved -> {
                this.status = PaymentStatus.CAPTURED;
                this.gatewayRef = approved.gatewayRef();
            }
            case GatewayResult.Declined declined -> {
                this.status = PaymentStatus.DECLINED;
                this.failureReason = declined.reason();
            }
            case GatewayResult.Failed failed -> {
                this.status = PaymentStatus.FAILED;
                this.failureReason = failed.reason();
            }
        }
        this.completedAt = now;
    }
}
