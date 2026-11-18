/**
 * The resource-server mechanism every HTTP service shares. Non-null by default, as Spring
 * Framework 7 and Spring Security 7 are: their callbacks this package implements
 * ({@code AuthenticationEntryPoint}, {@code OncePerRequestFilter},
 * {@code OAuth2TokenValidator}) are {@code @NullMarked}.
 */
@NullMarked
package com.ticketwaitingroom.security;

import org.jspecify.annotations.NullMarked;
