package ai.ultimate.config;

import ai.ultimate.security.jwt.JwtAuthenticationFilter;
import ai.ultimate.security.ratelimit.FixedWindowRateLimiter;
import ai.ultimate.security.ratelimit.RateLimitingWebFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/webjars/**"
    };

    private static final String[] PUBLIC_HEALTH_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**"
    };

    private static final String[] ADMIN_ACTUATOR_ENDPOINTS = {
            "/actuator",
            "/actuator/**"
    };

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final ObjectProvider<FixedWindowRateLimiter> rateLimiter;
    private final ObjectProvider<UltimateProperties> ultimateProperties;

    @Bean
    public SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http) {
        ServerHttpSecurity configured = http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterBefore(
                        jwtAuthFilter,
                        SecurityWebFiltersOrder.AUTHENTICATION
                );

        FixedWindowRateLimiter limiter = rateLimiter.getIfAvailable();
        UltimateProperties properties = ultimateProperties.getIfAvailable();
        if (limiter != null && properties != null) {
            configured = configured.addFilterAfter(
                    new RateLimitingWebFilter(
                            limiter,
                            properties.security().rateLimiting()),
                    SecurityWebFiltersOrder.AUTHENTICATION);
        }

        return configured
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_HEALTH_ENDPOINTS).permitAll()
                        .pathMatchers(ADMIN_ACTUATOR_ENDPOINTS).hasRole("ADMIN")
                        .pathMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyExchange().authenticated()
                )
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Argon2id: memory=65536KB, iterations=3,
        //           parallelism=1, hashLength=32, saltLength=16
        return new Argon2PasswordEncoder(
                16,
                32,
                1,
                65536,
                3);
    }
}
