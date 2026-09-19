package ai.ultimate.security.ratelimit;

import ai.ultimate.config.UltimateProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class RateLimitingConfiguration {

    @Bean
    public FixedWindowRateLimiter fixedWindowRateLimiter(
            UltimateProperties properties) {
        UltimateProperties.RateLimitingProperties rateLimiting =
                properties.security().rateLimiting();
        validate(rateLimiting);
        return new FixedWindowRateLimiter(
                Clock.systemUTC(),
                Duration.ofMinutes(1),
                rateLimiting.maxTrackedKeys());
    }

    private static void validate(
            UltimateProperties.RateLimitingProperties properties) {
        if (properties.chatRequestsPerMinute() < 1
                || properties.authAttemptsPerMinute() < 1
                || properties.adminRequestsPerMinute() < 1) {
            throw new IllegalStateException(
                    "Rate-limit request budgets must be positive");
        }
        if (properties.maxTrackedKeys() < 1) {
            throw new IllegalStateException(
                    "Rate-limit max-tracked-keys must be positive");
        }
    }
}
