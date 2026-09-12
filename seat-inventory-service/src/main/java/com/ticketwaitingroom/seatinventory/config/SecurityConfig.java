package com.ticketwaitingroom.seatinventory.config;

import com.ticketwaitingroom.security.EndpointAuthorization;
import com.ticketwaitingroom.security.TicketingAuthorities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * This service's path rules; the mechanism is {@code security-starter}'s.
 *
 * <p>There is <b>no fan door</b> here. A customer never talks to seat inventory directly:
 * they ask for a purchase, and the saga holds, confirms or releases the seat on their behalf
 * with its own machine-to-machine token. Seeding is an operator action. So every endpoint
 * is ops-only, and a customer's token — valid, signed by the same pool — is refused with
 * 403 at the filter, before any handler runs. That is the whole seat-locking surface closed
 * to anyone but the orchestrator.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    EndpointAuthorization everythingIsOps() {
        return registry -> registry.requestMatchers("/**").hasAuthority(TicketingAuthorities.OPS);
    }
}
