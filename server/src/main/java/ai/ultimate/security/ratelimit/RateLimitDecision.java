package ai.ultimate.security.ratelimit;

/**
 * Result of attempting to consume one request from a rate-limit window.
 *
 * @param allowed           whether the request may continue
 * @param limit             configured request limit for the window
 * @param remaining         requests still available after this decision
 * @param resetAfterSeconds seconds until a fresh window begins
 * @param retryAfterSeconds seconds a rejected caller should wait
 */
public record RateLimitDecision(
        boolean allowed,
        int limit,
        int remaining,
        long resetAfterSeconds,
        long retryAfterSeconds
) {

    static RateLimitDecision admitted(
            int limit,
            int remaining,
            long resetAfterSeconds) {
        return new RateLimitDecision(
                true,
                limit,
                remaining,
                resetAfterSeconds,
                0L);
    }

    static RateLimitDecision rejected(
            int limit,
            long resetAfterSeconds) {
        return new RateLimitDecision(
                false,
                limit,
                0,
                resetAfterSeconds,
                resetAfterSeconds);
    }
}
