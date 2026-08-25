package com.ticketwaitingroom.checkoutpayment.config;

import com.ticketwaitingroom.checkoutpayment.payment.MockPaymentGateway;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentGateway;
import com.ticketwaitingroom.checkoutpayment.payment.PaymentGatewayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the checkout beans. The gateway is a bean rather than a {@code new} so a real
 * provider can replace the mock without touching {@code CheckoutService}.
 */
@Configuration
@EnableConfigurationProperties({CheckoutProperties.class, PaymentGatewayProperties.class})
public class CheckoutConfig {

    @Bean
    public PaymentGateway paymentGateway(PaymentGatewayProperties properties) {
        return new MockPaymentGateway(properties);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
