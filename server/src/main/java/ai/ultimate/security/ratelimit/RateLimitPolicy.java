package ai.ultimate.security.ratelimit;

import ai.ultimate.config.UltimateProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;

import java.util.List;
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

    private static final List<String> AUTH_PREFIX =
            List.of("api", "v1", "auth");
    private static final List<String> ADMIN_PREFIX =
            List.of("api", "v1", "admin");
    private static final List<String> SETTINGS_PREFIX =
            List.of("api", "v1", "settings");
    private static final List<List<String>> EXPENSIVE_PREFIXES = List.of(
            List.of("api", "v1", "chat"),
            List.of("api", "v1", "agents"),
            List.of("api", "v1", "voice"),
            List.of("api", "v1", "memories"),
            List.of("api", "v1", "documents"));

    static RateLimitPolicy classify(
            HttpMethod method,
            PathContainer path) {
        if (method == null
                || method == HttpMethod.OPTIONS
                || path == null) {
            return null;
        }

        List<String> segments = path.elements().stream()
                .filter(PathContainer.PathSegment.class::isInstance)
                .map(PathContainer.PathSegment.class::cast)
                .map(PathContainer.PathSegment::valueToMatch)
                .toList();

        if (MUTATING_METHODS.contains(method)
                && matchesPrefix(segments, AUTH_PREFIX)) {
            return AUTH;
        }

        if (EXPENSIVE_METHODS.contains(method)
                && matchesAnyPrefix(segments, EXPENSIVE_PREFIXES)) {
            return CHAT;
        }

        if (matchesPrefix(segments, ADMIN_PREFIX)
                || (MUTATING_METHODS.contains(method)
                && matchesPrefix(segments, SETTINGS_PREFIX))) {
            return ADMIN;
        }

        return null;
    }

    /**
     * Compatibility entrypoint for direct callers and unit tests. Parsing
     * through PathContainer keeps the same matrix-parameter semantics used by
     * WebFlux handler matching instead of treating raw semicolon content as a
     * distinct route segment.
     */
    static RateLimitPolicy classify(
            HttpMethod method,
            String path) {
        return classify(
                method,
                path == null ? null : PathContainer.parsePath(path));
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
            List<String> path,
            List<List<String>> prefixes) {
        for (List<String> prefix : prefixes) {
            if (matchesPrefix(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPrefix(
            List<String> path,
            List<String> prefix) {
        return path.size() >= prefix.size()
                && path.subList(0, prefix.size()).equals(prefix);
    }
}
