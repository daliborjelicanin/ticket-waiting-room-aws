package com.ticketwaitingroom.checkoutpayment.order;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A customer's intent to buy specific held seats, and the record of whether that
 * succeeded. This is the transactional half of the system: unlike a seat, an order is not
 * a single contended item but a small graph (order plus its seats plus its payments) whose
 * consistency has to hold across rows.
 *
 * <p>State transitions are methods rather than a setter, and each one refuses an illegal
 * move. The status column is also constrained in the schema, so an invalid value cannot be
 * written even by something that is not this class.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "customer_ref", nullable = false)
    private String customerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_seats", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderSeat> seats = new ArrayList<>();

    private Order(UUID id, String eventId, String customerRef, long amountCents, String currency,
                  String idempotencyKey, List<OrderSeat> seats, Instant now) {
        this.id = id;
        this.eventId = eventId;
        this.customerRef = customerRef;
        this.status = OrderStatus.PENDING;
        this.amountCents = amountCents;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.seats = new ArrayList<>(seats);
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Create a PENDING order for seats already held in the inventory service.
     *
     * <p>The id is supplied by the caller rather than minted here. Checkout is started
     * through API Gateway's Step Functions integration, which answers with an execution
     * arn and nothing else — so unless the client already knows the id, it has no way to
     * poll for the outcome of the purchase it just started. Choosing it up front also makes
     * the primary key a second line of defence beside {@code uq_orders_idempotency_key}:
     * a replayed request cannot become a second order even if its idempotency key is lost.
     */
    public static Order pending(UUID id, String eventId, String customerRef, long amountCents, String currency,
                                String idempotencyKey, List<OrderSeat> seats, Instant now) {
        if (id == null) {
            // A 400 with a name, rather than a null primary key surfacing as a 500 from JPA.
            throw new IllegalArgumentException("An order must be created with a client-supplied orderId");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Order amount must be positive, was " + amountCents);
        }
        if (seats.isEmpty()) {
            throw new IllegalArgumentException("An order must cover at least one seat");
        }
        return new Order(id, eventId, customerRef, amountCents, currency, idempotencyKey, seats, now);
    }

    public List<OrderSeat> seats() {
        return Collections.unmodifiableList(seats);
    }

    public boolean isPending() {
        return status == OrderStatus.PENDING;
    }

    /**
     * Mark the order paid. Guarded on PENDING so a duplicate completion — a retried saga
     * step, a redelivered message — cannot move an order out of a terminal state.
     */
    public void markPaid(Instant now) {
        transitionFromPending(OrderStatus.PAID, now);
    }

    /** Mark the order failed. This is what tells the saga to release the seat holds. */
    public void markFailed(Instant now) {
        transitionFromPending(OrderStatus.FAILED, now);
    }

    /** Abandon the order before payment. Also releases seat holds via the saga. */
    public void markCancelled(Instant now) {
        transitionFromPending(OrderStatus.CANCELLED, now);
    }

    private void transitionFromPending(OrderStatus target, Instant now) {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "Order " + id + " is " + status + " and cannot become " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }
}
