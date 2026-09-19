package ai.ultimate.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, bounded fixed-window rate limiter.
 *
 * <p>Each key has exactly one immutable counter per window. Updates use
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)},
 * so concurrent requests for the same caller cannot over-admit. A semaphore
 * strictly bounds the number of tracked keys, while opportunistic cleanup
 * releases capacity after windows expire.</p>
 */
public final class FixedWindowRateLimiter {

    private final Clock clock;
    private final long windowMillis;
    private final ConcurrentHashMap<String, WindowCounter> windows;
    private final Semaphore keySlots;
    private final AtomicLong nextCleanupAtMillis;

    public FixedWindowRateLimiter(int maxTrackedKeys) {
        this(
                Clock.systemUTC(),
                Duration.ofMinutes(1),
                maxTrackedKeys);
    }

    public FixedWindowRateLimiter(
            Clock clock,
            Duration window,
            int maxTrackedKeys) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "window must be greater than zero");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException(
                    "maxTrackedKeys must be at least one");
        }

        this.windowMillis = window.toMillis();
        if (windowMillis < 1L) {
            throw new IllegalArgumentException(
                    "window must be at least one millisecond");
        }
        this.windows = new ConcurrentHashMap<>();
        this.keySlots = new Semaphore(maxTrackedKeys);
        long initialWindowStart = windowStart(clock.millis());
        this.nextCleanupAtMillis = new AtomicLong(
                safeAdd(initialWindowStart, windowMillis));
    }

    /**
     * Consume one request for {@code key} from a window capped at
     * {@code limit} requests.
     */
    public RateLimitDecision acquire(String key, int limit) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "limit must be at least one");
        }

        long now = clock.millis();
        long windowStart = windowStart(now);
        long resetAfterSeconds = secondsUntilReset(
                now,
                windowStart);

        cleanupIfDue(now, windowStart);
        if (keySlots.availablePermits() == 0) {
            cleanupExpired(windowStart);
        }

        AtomicReference<RateLimitDecision> result =
                new AtomicReference<>();

        windows.compute(key, (ignored, current) -> {
            if (current == null) {
                if (!keySlots.tryAcquire()) {
                    result.set(RateLimitDecision.rejected(
                            limit,
                            resetAfterSeconds));
                    return null;
                }

                result.set(RateLimitDecision.admitted(
                        limit,
                        limit - 1,
                        resetAfterSeconds));
                return new WindowCounter(windowStart, 1);
            }

            if (current.windowStartMillis() != windowStart) {
                result.set(RateLimitDecision.admitted(
                        limit,
                        limit - 1,
                        resetAfterSeconds));
                return new WindowCounter(windowStart, 1);
            }

            if (current.count() >= limit) {
                result.set(RateLimitDecision.rejected(
                        limit,
                        resetAfterSeconds));
                return current;
            }

            int nextCount = current.count() + 1;
            result.set(RateLimitDecision.admitted(
                    limit,
                    Math.max(0, limit - nextCount),
                    resetAfterSeconds));
            return new WindowCounter(windowStart, nextCount);
        });

        RateLimitDecision decision = result.get();
        if (decision == null) {
            throw new IllegalStateException(
                    "rate-limit decision was not produced");
        }
        return decision;
    }

    int trackedKeyCount() {
        return windows.size();
    }

    private void cleanupIfDue(long now, long currentWindowStart) {
        long dueAt = nextCleanupAtMillis.get();
        if (now < dueAt) {
            return;
        }
        long nextDue = safeAdd(
                currentWindowStart,
                windowMillis);
        if (nextCleanupAtMillis.compareAndSet(dueAt, nextDue)) {
            cleanupExpired(currentWindowStart);
        }
    }

    private void cleanupExpired(long currentWindowStart) {
        windows.forEach((key, counter) -> {
            if (counter.windowStartMillis() < currentWindowStart
                    && windows.remove(key, counter)) {
                keySlots.release();
            }
        });
    }

    private long windowStart(long timestampMillis) {
        return Math.floorDiv(timestampMillis, windowMillis)
                * windowMillis;
    }

    private long secondsUntilReset(
            long now,
            long windowStart) {
        long resetAt = safeAdd(windowStart, windowMillis);
        long remainingMillis = Math.max(1L, resetAt - now);
        return Math.max(
                1L,
                Math.floorDiv(
                        safeAdd(remainingMillis, 999L),
                        1000L));
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private record WindowCounter(
            long windowStartMillis,
            int count
    ) {}
}
