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

    private final RequestStats requestStats;

    public RequestStatsHealthIndicator(AiRequestLogger requestLogger) {
        this.requestStats = requestLogger.stats() == null
                ? new RequestStats()
                : requestLogger.stats() != null ? requestLoggerStats(requestLogger) : new RequestStats();
    }

    private static RequestStats requestLoggerStats(AiRequestLogger requestLogger) {
        // Keep the dependency surface intentionally narrow: the logger remains the owner
        // of request telemetry and only its immutable snapshot crosses this boundary.
        RequestStats stats = new RequestStats();
        RequestStats.Snapshot snapshot = requestLogger.stats();
        for (long i = 0; i < snapshot.started(); i++) {
            stats.requestStarted();
        }
        if (snapshot.completed() > 0) {
            stats.requestCompleted((int) Math.min(Integer.MAX_VALUE, snapshot.totalTokens()),
                    snapshot.totalDurationMs());
        }
        for (long i = 0; i < snapshot.failed(); i++) {
            stats.requestFailed();
        }
        return stats;
    }

    @Override
    public Health health() {
        RequestStats.Snapshot snapshot = requestStats.snapshot();
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
