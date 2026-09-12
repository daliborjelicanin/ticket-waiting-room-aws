package com.ticketwaitingroom.waitingroom.config;

import com.ticketwaitingroom.security.EndpointAuthorization;
import com.ticketwaitingroom.security.TicketingAuthorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * This service's path rules. Everything else — verifying the JWT, refusing anonymous
 * requests, the stateless chain — is {@code security-starter}'s, and every endpoint not
 * named here needs a valid token and nothing more.
 *
 * <p>Join and position are fan-door endpoints: any customer, acting as their own
 * {@code sub}. Admit pops real people off the head of a real queue, so it is ops-only — a
 * customer's token, valid and from the same pool, is refused with 403 before the controller
 * runs.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    EndpointAuthorization opsEndpoints() {
        return registry -> registry
                .requestMatchers(HttpMethod.POST, "/events/{eventId}/queue/admit")
                .hasAuthority(TicketingAuthorities.OPS);
    }
}
