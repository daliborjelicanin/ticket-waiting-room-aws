package com.ticketwaitingroom.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * A service's contribution to the shared filter chain: the paths that need more than a
 * valid token.
 *
 * <p>This is the only security code a service writes. Declare one as a bean and the
 * auto-configuration applies it — every such bean, in order — before its own terminal rule
 * that everything else must be authenticated. A service with nothing to add declares none,
 * and gets a chain where every request needs a valid token and nothing needs more.
 *
 * <pre>{@code
 * @Bean
 * EndpointAuthorization opsEndpoints() {
 *     return registry -> registry
 *             .requestMatchers(HttpMethod.POST, "/events/{eventId}/queue/admit")
 *             .hasAuthority(TicketingAuthorities.OPS);
 * }
 * }</pre>
 *
 * <p>To require the ops scope for an entire service, match {@code /**} — the terminal
 * {@code anyRequest()} the auto-configuration adds afterwards is then simply never reached.
 * Do not call {@code anyRequest()} here yourself: Spring Security refuses a second one.
 */
@FunctionalInterface
public interface EndpointAuthorization {

    void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry);
}
