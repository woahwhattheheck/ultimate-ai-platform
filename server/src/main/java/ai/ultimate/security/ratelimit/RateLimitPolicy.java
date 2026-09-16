package ai.ultimate.security.ratelimit;

import ai.ultimate.config.UltimateProperties;
import org.springframework.http.HttpMethod;

import java.util.Set;

/** Request classes with independent configurable quotas. */
enum RateLimitPolicy {
    AUTH,
    CHAT,
    ADMIN;

    private static final Set<HttpMethod> MUTATING_METHODS = Set.of(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE);

    private static final Set<HttpMethod> EXPENSIVE_METHODS = Set.of(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH);

    private static final String[] EXPENSIVE_PREFIXES = {
            "/api/v1/chat",
            "/api/v1/agents",
            "/api/v1/voice",
            "/api/v1/memories",
            "/api/v1/documents"
    };

    static RateLimitPolicy classify(
            HttpMethod method,
            String path) {
        if (method == null
                || method == HttpMethod.OPTIONS
                || path == null) {
            return null;
        }

        if (MUTATING_METHODS.contains(method)
                && matchesPrefix(path, "/api/v1/auth")) {
            return AUTH;
        }

        if (EXPENSIVE_METHODS.contains(method)
                && matchesAnyPrefix(path, EXPENSIVE_PREFIXES)) {
            return CHAT;
        }

        if (matchesPrefix(path, "/api/v1/admin")
                || (MUTATING_METHODS.contains(method)
                && matchesPrefix(path, "/api/v1/settings"))) {
            return ADMIN;
        }

        return null;
    }

    int limit(
            UltimateProperties.RateLimitingProperties properties) {
        return switch (this) {
            case AUTH -> properties.authAttemptsPerMinute();
            case CHAT -> properties.chatRequestsPerMinute();
            case ADMIN -> properties.adminRequestsPerMinute();
        };
    }

    String keyPrefix() {
        return name().toLowerCase();
    }

    private static boolean matchesAnyPrefix(
            String path,
            String[] prefixes) {
        for (String prefix : prefixes) {
            if (matchesPrefix(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPrefix(
            String path,
            String prefix) {
        return path.equals(prefix)
                || path.startsWith(prefix + "/");
    }
}
