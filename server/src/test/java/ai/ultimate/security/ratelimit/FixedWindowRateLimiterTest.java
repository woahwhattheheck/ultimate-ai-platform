package ai.ultimate.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void admitsExactlyConfiguredQuotaThenRejects() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-15T21:00:00Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                clock,
                Duration.ofMinutes(1),
                100);

        assertThat(limiter.acquire("chat:user-1", 2))
                .extracting(
                        RateLimitDecision::allowed,
                        RateLimitDecision::remaining,
                        RateLimitDecision::resetAfterSeconds)
                .containsExactly(true, 1, 60L);
        assertThat(limiter.acquire("chat:user-1", 2))
                .extracting(
                        RateLimitDecision::allowed,
                        RateLimitDecision::remaining)
                .containsExactly(true, 0);
        assertThat(limiter.acquire("chat:user-1", 2))
                .extracting(
                        RateLimitDecision::allowed,
                        RateLimitDecision::remaining,
                        RateLimitDecision::retryAfterSeconds)
                .containsExactly(false, 0, 60L);
    }

    @Test
    void isolatesKeysAndResetsAtWindowBoundary() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-15T21:00:30Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                clock,
                Duration.ofMinutes(1),
                100);

        assertThat(limiter.acquire("chat:user-a", 1).allowed())
                .isTrue();
        assertThat(limiter.acquire("chat:user-a", 1).allowed())
                .isFalse();
        assertThat(limiter.acquire("chat:user-b", 1).allowed())
                .isTrue();

        clock.advance(Duration.ofSeconds(30));

        RateLimitDecision nextWindow = limiter.acquire(
                "chat:user-a",
                1);
        assertThat(nextWindow.allowed()).isTrue();
        assertThat(nextWindow.resetAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void boundsTrackedKeysAndReleasesExpiredCapacity() {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-15T21:00:00Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                clock,
                Duration.ofMinutes(1),
                2);

        assertThat(limiter.acquire("auth:a", 5).allowed()).isTrue();
        assertThat(limiter.acquire("auth:b", 5).allowed()).isTrue();
        assertThat(limiter.acquire("auth:c", 5).allowed()).isFalse();
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.acquire("auth:c", 5).allowed()).isTrue();
        assertThat(limiter.trackedKeyCount()).isLessThanOrEqualTo(2);
    }

    @Test
    void concurrentRequestsCannotOverAdmit() throws Exception {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-15T21:00:00Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                clock,
                Duration.ofMinutes(1),
                100);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.acquire(
                            "chat:shared-user",
                            25).allowed();
                }));
            }

            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    admitted++;
                }
            }

            assertThat(admitted).isEqualTo(25);
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = new AtomicReference<>(instant);
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant(), requestedZone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
