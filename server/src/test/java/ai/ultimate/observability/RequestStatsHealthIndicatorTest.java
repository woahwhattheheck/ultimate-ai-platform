package ai.ultimate.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestStatsHealthIndicatorTest {

    @Test
    void exposesCurrentAggregateSnapshot() {
        AiRequestLogger logger = new AiRequestLogger();
        RequestStatsHealthIndicator indicator = new RequestStatsHealthIndicator(logger);

        logger.logRequestStart("user", "session", 2);
        logger.logRequestComplete("user", "session", 120, 3000);
        logger.logRequestError("user", "session", "timeout");

        var health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails())
                .containsEntry("started", 1L)
                .containsEntry("completed", 1L)
                .containsEntry("failed", 1L)
                .containsEntry("totalTokens", 120L)
                .containsEntry("totalDurationMs", 3000L)
                .containsEntry("averageTokensPerSecond", 40.0);
    }
}
