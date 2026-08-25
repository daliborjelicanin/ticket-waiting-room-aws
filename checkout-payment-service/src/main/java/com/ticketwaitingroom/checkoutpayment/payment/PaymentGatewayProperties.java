package com.ticketwaitingroom.checkoutpayment.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Chaos knobs for {@link MockPaymentGateway}.
 *
 * <p>Both rates default to zero so that behaviour is deterministic unless a test or a load
 * run asks otherwise — a gateway that fails randomly by default makes every other test in
 * the suite flaky.
 *
 * @param declineRate probability in [0,1] that an otherwise-unremarkable token is declined
 * @param errorRate   probability in [0,1] that the gateway "cannot be reached"
 * @param latency     artificial delay per call, for making races reproducible under load
 */
@ConfigurationProperties(prefix = "payment-gateway")
public record PaymentGatewayProperties(
        double declineRate,
        double errorRate,
        Duration latency
) {
}
