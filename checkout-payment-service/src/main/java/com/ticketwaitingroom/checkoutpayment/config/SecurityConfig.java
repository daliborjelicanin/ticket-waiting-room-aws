package com.ticketwaitingroom.checkoutpayment.config;

import com.ticketwaitingroom.security.EndpointAuthorization;
import com.ticketwaitingroom.security.TicketingAuthorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * This service's path rules; the mechanism is {@code security-starter}'s.
 *
 * <p>The two doors split by what moves money. Create-order, charge and refund are only ever called
 * by Step Functions with a machine-to-machine token, so they are ops-only; the saga names
 * the customer in the body and is trusted to, because its input was built by API Gateway
 * from that customer's own verified token. Read and cancel are fan-door: any valid token,
 * scoped to its own {@code sub} by the service.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    EndpointAuthorization sagaEndpoints() {
        return registry -> registry
                .requestMatchers(HttpMethod.POST, "/orders").hasAuthority(TicketingAuthorities.OPS)
                .requestMatchers(HttpMethod.POST, "/orders/{orderId}/payment").hasAuthority(TicketingAuthorities.OPS)
                .requestMatchers(HttpMethod.POST, "/orders/{orderId}/refund").hasAuthority(TicketingAuthorities.OPS);
    }
}
