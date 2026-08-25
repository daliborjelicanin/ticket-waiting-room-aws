package com.ticketwaitingroom.checkoutpayment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the checkout service.
 *
 * @param currency ISO-4217 code every order is priced in. Single-currency by design for
 *                 now — multi-currency needs FX handling that does not belong in a
 *                 checkout service.
 */
@ConfigurationProperties(prefix = "checkout")
public record CheckoutProperties(
        String currency
) {
}
