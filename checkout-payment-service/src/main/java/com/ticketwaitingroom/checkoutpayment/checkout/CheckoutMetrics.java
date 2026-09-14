package com.ticketwaitingroom.checkoutpayment.checkout;

import com.ticketwaitingroom.checkoutpayment.payment.GatewayResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Checkout as the dashboard sees it: how many orders, how each charge ended, and how long
 * the gateway took to say so.
 *
 * <dl>
 *   <dt>{@code checkout.orders} · {@code outcome=created|replayed}</dt>
 *   <dd>{@code replayed} is a create whose idempotency key already had an order — the saga
 *       retrying. Ordinary, and worth seeing when it stops being rare.</dd>
 *   <dt>{@code checkout.charges} · {@code outcome=captured|declined|failed|replayed|in_flight|not_payable}</dt>
 *   <dd>One increment per charge call, by what the caller got back. {@code captured} over
 *       {@code waiting_room.admissions} is the conversion rate. {@code in_flight} is the
 *       partial unique index refusing a second live attempt and {@code not_payable} is an
 *       attempt that arrived after the order had already settled — under the concurrency
 *       test the two together are N−1 of every N, split by how late each loser was.</dd>
 *   <dt>{@code checkout.gateway.latency}</dt>
 *   <dd>The gateway call alone — the network the transaction is deliberately not held
 *       across. The mock's configured latency shows up here first, and in a real provider's
 *       outage this is the number that climbs while the pool stays healthy.</dd>
 * </dl>
 */
@Component
@RequiredArgsConstructor
public class CheckoutMetrics {

    private final MeterRegistry registry;

    public void orderCreated(boolean created) {
        registry.counter("checkout.orders", "outcome", created ? "created" : "replayed").increment();
    }

    public void charged(String outcome) {
        registry.counter("checkout.charges", "outcome", outcome).increment();
    }

    public GatewayResult timeGateway(Supplier<GatewayResult> call) {
        Timer.Sample sample = Timer.start(registry);
        try {
            return call.get();
        } finally {
            sample.stop(registry.timer("checkout.gateway.latency"));
        }
    }

    /** The counter label for a gateway answer. */
    public static String outcomeOf(GatewayResult result) {
        return switch (result) {
            case GatewayResult.Approved _ -> "captured";
            case GatewayResult.Declined _ -> "declined";
            case GatewayResult.Failed _ -> "failed";
        };
    }
}
