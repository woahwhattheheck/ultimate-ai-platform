package ai.ultimate.security.ratelimit;

import ai.ultimate.config.UltimateProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Reactive API rate limiting applied after authentication.
 *
 * <p>Authentication attempts are isolated by client address. Authenticated
 * expensive and administrative calls are isolated by principal, with a
 * client-address fallback for defense in depth.</p>
 */
public final class RateLimitingWebFilter implements WebFilter {

    public static final String LIMIT_HEADER = "RateLimit-Limit";
    public static final String REMAINING_HEADER =
            "RateLimit-Remaining";
    public static final String RESET_HEADER = "RateLimit-Reset";

    private static final int MAX_CLIENT_KEY_LENGTH = 128;

    private final FixedWindowRateLimiter rateLimiter;
    private final UltimateProperties.RateLimitingProperties properties;

    public RateLimitingWebFilter(
            FixedWindowRateLimiter rateLimiter,
            UltimateProperties.RateLimitingProperties properties) {
        this.rateLimiter = Objects.requireNonNull(
                rateLimiter,
                "rateLimiter");
        this.properties = Objects.requireNonNull(
                properties,
                "properties");
    }

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            WebFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }

        RateLimitPolicy policy = RateLimitPolicy.classify(
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value());
        if (policy == null) {
            return chain.filter(exchange);
        }

        int limit = policy.limit(properties);

        return resolveClientKey(exchange, policy)
                .flatMap(clientKey -> {
                    String bucketKey = policy.keyPrefix()
                            + ':' + clientKey;
                    RateLimitDecision decision =
                            rateLimiter.acquire(
                                    bucketKey,
                                    limit);
                    writeRateLimitHeaders(
                            exchange,
                            decision);

                    if (decision.allowed()) {
                        return chain.filter(exchange);
                    }
                    return writeRejectedResponse(
                            exchange,
                            decision);
                });
    }

    private Mono<String> resolveClientKey(
            ServerWebExchange exchange,
            RateLimitPolicy policy) {
        String addressKey = "address:"
                + resolveClientAddress(exchange);
        if (policy == RateLimitPolicy.AUTH) {
            return Mono.just(addressKey);
        }

        return ReactiveSecurityContextHolder
                .getContext()
                .flatMap(context -> Mono.justOrEmpty(
                        context.getAuthentication()))
                .filter(Authentication::isAuthenticated)
                .flatMap(authentication -> Mono.justOrEmpty(
                        authentication.getName()))
                .filter(name -> !name.isBlank())
                .map(this::normalizePrincipal)
                .map(name -> "principal:" + name)
                .defaultIfEmpty(addressKey);
    }

    private String normalizePrincipal(String principal) {
        String normalized = principal.trim();
        if (normalized.length() > MAX_CLIENT_KEY_LENGTH) {
            return normalized.substring(0, MAX_CLIENT_KEY_LENGTH);
        }
        return normalized;
    }

    private String resolveClientAddress(
            ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        if (properties.trustForwardedHeaders()) {
            String forwarded = forwardedFor(
                    headers.getFirst("Forwarded"));
            if (forwarded != null) {
                return forwarded;
            }

            String xForwardedFor = firstForwardedAddress(
                    headers.getFirst("X-Forwarded-For"));
            if (xForwardedFor != null) {
                return xForwardedFor;
            }
        }

        InetSocketAddress remoteAddress =
                exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return "unknown";
        }
        if (remoteAddress.getAddress() != null) {
            return normalizeAddress(
                    remoteAddress.getAddress().getHostAddress());
        }
        return normalizeAddress(remoteAddress.getHostString());
    }

    private String firstForwardedAddress(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        String first = comma >= 0
                ? header.substring(0, comma)
                : header;
        return normalizeOptionalAddress(first);
    }

    private String forwardedFor(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }

        int comma = header.indexOf(',');
        String firstEntry = comma >= 0
                ? header.substring(0, comma)
                : header;
        for (String parameter : firstEntry.split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.regionMatches(
                    true,
                    0,
                    "for=",
                    0,
                    4)) {
                return normalizeOptionalAddress(
                        trimmed.substring(4));
            }
        }
        return null;
    }

    private String normalizeOptionalAddress(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalizeAddress(value);
        if (normalized.isBlank()
                || "unknown".equals(normalized)
                || normalized.startsWith("_")) {
            return null;
        }
        return normalized;
    }

    private String normalizeAddress(String value) {
        String normalized = value == null
                ? ""
                : value.trim();
        if (normalized.length() >= 2
                && normalized.startsWith("\"")
                && normalized.endsWith("\"")) {
            normalized = normalized.substring(
                    1,
                    normalized.length() - 1);
        }

        if (normalized.startsWith("[")) {
            int close = normalized.indexOf(']');
            if (close > 1) {
                normalized = normalized.substring(1, close);
            }
        } else {
            int firstColon = normalized.indexOf(':');
            int lastColon = normalized.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                String possiblePort = normalized.substring(
                        lastColon + 1);
                if (possiblePort.chars()
                        .allMatch(Character::isDigit)) {
                    normalized = normalized.substring(
                            0,
                            lastColon);
                }
            }
        }

        normalized = normalized
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "unknown";
        }
        if (normalized.length() > MAX_CLIENT_KEY_LENGTH) {
            return normalized.substring(0, MAX_CLIENT_KEY_LENGTH);
        }
        return normalized;
    }

    private void writeRateLimitHeaders(
            ServerWebExchange exchange,
            RateLimitDecision decision) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(LIMIT_HEADER, String.valueOf(decision.limit()));
        headers.set(
                REMAINING_HEADER,
                String.valueOf(decision.remaining()));
        headers.set(
                RESET_HEADER,
                String.valueOf(decision.resetAfterSeconds()));
    }

    private Mono<Void> writeRejectedResponse(
            ServerWebExchange exchange,
            RateLimitDecision decision) {
        exchange.getResponse().setStatusCode(
                HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(
                HttpHeaders.RETRY_AFTER,
                String.valueOf(decision.retryAfterSeconds()));
        exchange.getResponse().getHeaders().setContentType(
                MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl(
                "no-store");

        String path = exchange.getRequest().getPath().value();
        String body = "{"
                + "\"status\":429,"
                + "\"error\":\"Too Many Requests\","
                + "\"code\":\"RATE_LIMIT_EXCEEDED\","
                + "\"message\":\"Request quota exhausted\","
                + "\"path\":\"" + escapeJson(path) + "\","
                + "\"limit\":" + decision.limit() + ','
                + "\"remaining\":" + decision.remaining() + ','
                + "\"resetAfterSeconds\":"
                + decision.resetAfterSeconds()
                + '}';

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(bytes);
        return exchange.getResponse().writeWith(
                Mono.just(buffer));
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(
                                "\\u%04x",
                                (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
