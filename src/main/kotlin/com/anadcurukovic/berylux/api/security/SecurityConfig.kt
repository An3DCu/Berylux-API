package com.anadcurukovic.berylux.api.security

import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

/** Closed by default: every route needs a signed-in player unless it's in [OPEN]. */
@Configuration
class SecurityConfig {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .authorizeHttpRequests { routes ->
                OPEN.forEach { (method, path) -> routes.requestMatchers(method, path).permitAll() }
                // Spring's own forward to render an error. Without it a missing page would answer 401, not 404.
                routes.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                routes.anyRequest().authenticated()
            }
            // A signed-in player sends an access token: an HS256 JWT from this API (AccessTokens).
            .oauth2ResourceServer { it.jwt { } }
            // Tokens, not cookies: no session to keep and no cookie for another site to ride on.
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            // A closed route answers 401, "sign in first", not 403.
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .build()

    /** No built-in user: without this Spring Boot makes one and prints its password. */
    @Bean
    fun noUsers(): UserDetailsService = InMemoryUserDetailsManager()

    companion object {
        /**
         * Every route anyone can reach without signing in. The docs only exist when running
         * locally; anywhere else these paths answer 404.
         */
        val OPEN: List<Pair<HttpMethod, String>> = listOf(
            HttpMethod.GET to "/actuator/health",
            HttpMethod.POST to "/auth/register",
            HttpMethod.POST to "/auth/login",
            HttpMethod.POST to "/auth/refresh",
            HttpMethod.GET to "/v3/api-docs",
            HttpMethod.GET to "/v3/api-docs/**",
            HttpMethod.GET to "/v3/api-docs.yaml",
            HttpMethod.GET to "/swagger-ui.html",
            HttpMethod.GET to "/swagger-ui/**",
        )
    }
}
