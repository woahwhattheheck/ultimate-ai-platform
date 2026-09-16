package ai.ultimate.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes aggregate AI request telemetry through the standard Actuator health surface.
 * No request content, identity, prompt, response, or error text is retained.
 */
@Component
public final class RequestStatsHealthIndicator implements HealthIndicator {

    private final AiRequestLogger requestLogger;

    public RequestStatsHealthIndicator(AiRequestLogger requestLogger) {
        this.requestLogger = requestLogger;
    }

    @Override
    public Health health() {
        RequestStats.Snapshot snapshot = requestLogger.stats();
        return Health.up()
                .withDetail("started", snapshot.started())
                .withDetail("completed", snapshot.completed())
                .withDetail("failed", snapshot.failed())
                .withDetail("totalTokens", snapshot.totalTokens())
                .withDetail("totalDurationMs", snapshot.totalDurationMs())
                .withDetail("averageTokensPerSecond", snapshot.averageTokensPerSecond())
                .build();
    }
}
