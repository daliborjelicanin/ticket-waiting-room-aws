package com.ticketwaitingroom.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The auto-configuration's conditions: what it contributes, when it backs off, and what
 * it refuses to start without. The chain's <em>behaviour</em> is proven in
 * {@link TicketingSecurityFilterChainTest}, through real requests.
 */
class TicketingSecurityAutoConfigurationTest {

    private static final String ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TicketingSecurityAutoConfiguration.class));

    @Test
    void contributesADecoderAndAChainWhenAnIssuerIsConfigured() {
        runner.withPropertyValues(ISSUER_PROPERTY + "=https://cognito.test.invalid/").run(context -> {
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasSingleBean(JwtDecoder.class);
            // Lazy by construction: the placeholder issuer above is never contacted, or this
            // context would not have started.
            assertThat(context.getBean(JwtDecoder.class)).isInstanceOf(SupplierJwtDecoder.class);
        });
    }

    @Test
    void refusesToStartWithoutAnIssuer() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining(ISSUER_PROPERTY);
        });
    }

    @Test
    void backsOffWhenTheServiceDefinesItsOwnDecoder() {
        runner.withPropertyValues(ISSUER_PROPERTY + "=https://cognito.test.invalid/")
                .withUserConfiguration(OwnDecoder.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    assertThat(context.getBean(JwtDecoder.class)).isNotInstanceOf(SupplierJwtDecoder.class);
                });
    }

    @Test
    void backsOffWhenTheServiceDefinesItsOwnChain() {
        runner.withPropertyValues(ISSUER_PROPERTY + "=https://cognito.test.invalid/")
                .withUserConfiguration(OwnChain.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(SecurityFilterChain.class);
                    assertThat(context).hasBean("ownChain");
                    assertThat(context).doesNotHaveBean("ticketingSecurityFilterChain");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnDecoder {

        @Bean
        JwtDecoder decoder() {
            return mock(JwtDecoder.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnChain {

        @Bean
        SecurityFilterChain ownChain(HttpSecurity http) {
            return http.build();
        }
    }
}
