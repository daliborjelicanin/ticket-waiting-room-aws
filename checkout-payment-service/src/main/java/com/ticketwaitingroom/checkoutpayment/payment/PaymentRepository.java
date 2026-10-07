package com.ticketwaitingroom.checkoutpayment.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /** At most one CAPTURED payment per order — {@code ux_payments_one_live_per_order}. */
    Optional<Payment> findByOrderIdAndStatus(UUID orderId, PaymentStatus status);
}
