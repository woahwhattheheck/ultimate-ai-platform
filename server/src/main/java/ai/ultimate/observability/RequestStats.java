package ai.ultimate.observability;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process request telemetry for the running server.
 *
 * <p>The counters contain no user content and are safe to update from concurrent
 * request threads. Values are snapshots of the current JVM only.</p>
 */
public final class RequestStats {

    private final AtomicLong started = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong totalDurationMs = new AtomicLong();

    public void requestStarted() {
        started.incrementAndGet();
    }

    public void requestCompleted(int tokens, long durationMs) {
        completed.incrementAndGet();
        totalTokens.addAndGet(Math.max(0, tokens));
        totalDurationMs.addAndGet(Math.max(0L, durationMs));
    }

    public void requestFailed() {
        failed.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                started.get(),
                completed.get(),
                failed.get(),
                totalTokens.get(),
                totalDurationMs.get()
        );
    }

    public record Snapshot(
            long started,
            long completed,
            long failed,
            long totalTokens,
            long totalDurationMs) {

        public double averageTokensPerSecond() {
            if (totalDurationMs <= 0) {
                return 0.0;
            }
            return totalTokens * 1000.0 / totalDurationMs;
        }
    }
}
