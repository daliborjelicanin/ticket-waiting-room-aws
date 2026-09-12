package com.ticketwaitingroom.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * Makes the service an OAuth2 resource server for the Cognito user pool.
 *
 * <p>Every request carries a JWT, and the service verifies it itself — signature, issuer,
 * expiry, and that it is an <em>access</em> token — against the pool's published keys.
 * Nothing is trusted because of where it came from. What a valid token may then do is the
 * service's business, declared as {@link EndpointAuthorization} beans; everything not so
 * declared merely needs a valid token.
 *
 * <p>Stateless and CSRF-free because there is no session to ride: the bearer token is the
 * whole credential, on every request.
 *
 * <p>Both beans back off to one a service defines itself, which is also how a test
 * substitutes a decoder. And the chain runs <em>before</em> Boot's own resource-server
 * auto-configuration, so Boot sees a {@code JwtDecoder} and a {@code SecurityFilterChain}
 * already present and contributes neither.
 *
 * <p><b>ALB health check.</b> {@code GET /} answers 401 rather than 404 under this chain.
 * The target group matcher is {@code 200-499}, so it stays healthy — and a stateless
 * resource server refusing an anonymous request is a correct answer from a working
 * process, not a fault.
 */
@AutoConfiguration(before = {
        SecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class,
        OAuth2ResourceServerWebSecurityAutoConfiguration.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OAuth2ResourceServerProperties.class)
@EnableWebSecurity
public class TicketingSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain ticketingSecurityFilterChain(HttpSecurity http, ObjectProvider<EndpointAuthorization> rules) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> {
                    rules.orderedStream().forEach(rule -> rule.configure(registry));
                    registry.anyRequest().authenticated();
                })
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Declared rather than left to Boot for one reason: Boot's decoder validates issuer
     * and timestamps only, and this one also insists on an access token — see
     * {@link AccessTokenValidator}.
     *
     * <p>Wrapped in a {@link SupplierJwtDecoder} so the pool is first contacted on the
     * first token rather than at startup. Without it, {@code withIssuerLocation(...).build()}
     * fetches the issuer's discovery document right here — the service could not boot with
     * the pool unreachable, and a test context could not load against a placeholder.
     * (Found by a test, not assumed.)
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder ticketingJwtDecoder(OAuth2ResourceServerProperties properties) {
        String issuer = properties.getJwt().getIssuerUri();
        if (!StringUtils.hasText(issuer)) {
            // A service that checks identity with no identity provider is misconfigured, not degraded.
            // Fail at startup, with the property named.
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri must be set to the Cognito user pool issuer");
        }
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), new AccessTokenValidator()));
            return decoder;
        });
    }
}
