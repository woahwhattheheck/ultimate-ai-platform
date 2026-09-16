package ai.ultimate.observability;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RequestStatsTest {

    @Test
    void tracksCompletedAndFailedRequestsWithoutUserContent() {
        RequestStats stats = new RequestStats();

        stats.requestStarted();
        stats.requestCompleted(100, 2000);
        stats.requestStarted();
        stats.requestFailed();

        RequestStats.Snapshot snapshot = stats.snapshot();

        assertThat(snapshot.started()).isEqualTo(2);
        assertThat(snapshot.completed()).isEqualTo(1);
        assertThat(snapshot.failed()).isEqualTo(1);
        assertThat(snapshot.totalTokens()).isEqualTo(100);
        assertThat(snapshot.totalDurationMs()).isEqualTo(2000);
        assertThat(snapshot.averageTokensPerSecond()).isEqualTo(50.0);
    }

    @Test
    void clampsInvalidCompletedValuesToZero() {
        RequestStats stats = new RequestStats();

        stats.requestCompleted(-10, -50);

        RequestStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.totalTokens()).isZero();
        assertThat(snapshot.totalDurationMs()).isZero();
        assertThat(snapshot.averageTokensPerSecond()).isZero();
    }

    @Test
    void remainsSafeUnderConcurrentUpdates() {
        RequestStats stats = new RequestStats();
        int requests = 1000;

        IntStream.range(0, requests).parallel().forEach(i -> {
            stats.requestStarted();
            stats.requestCompleted(2, 10);
        });

        RequestStats.Snapshot snapshot = stats.snapshot();
        assertThat(snapshot.started()).isEqualTo(requests);
        assertThat(snapshot.completed()).isEqualTo(requests);
        assertThat(snapshot.failed()).isZero();
        assertThat(snapshot.totalTokens()).isEqualTo(requests * 2L);
        assertThat(snapshot.totalDurationMs()).isEqualTo(requests * 10L);
    }
}
