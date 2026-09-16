package ai.ultimate.security.ratelimit;

import ai.ultimate.config.UltimateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingWebFilterTest {

    @Test
    void authQuotaUsesAddressAndReturnsStructured429() {
        RateLimitingWebFilter filter = filter(
                properties(true, 30, 2, 10, false));
        AtomicInteger chainCalls = new AtomicInteger();
        WebFilterChain chain = completingChain(chainCalls);

        MockServerWebExchange first = authExchange(
                "203.0.113.10");
        MockServerWebExchange second = authExchange(
                "203.0.113.10");
        MockServerWebExchange third = authExchange(
                "203.0.113.10");

        verifyComplete(filter.filter(first, chain));
        verifyComplete(filter.filter(second, chain));
        verifyComplete(filter.filter(third, chain));

        assertThat(chainCalls).hasValue(2);
        assertThat(first.getResponse().getHeaders()
                .getFirst(RateLimitingWebFilter.LIMIT_HEADER))
                .isEqualTo("2");
        assertThat(first.getResponse().getHeaders()
                .getFirst(RateLimitingWebFilter.REMAINING_HEADER))
                .isEqualTo("1");
        assertThat(third.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getResponse().getHeaders()
                .getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("60");
        assertThat(third.getResponse().getBodyAsString().block())
                .contains("\"code\":\"RATE_LIMIT_EXCEEDED\"")
                .contains("\"path\":\"/api/v1/auth/login\"")
                .contains("\"remaining\":0");
    }

    @Test
    void authenticatedPrincipalsHaveIndependentExpensiveQuotas() {
        RateLimitingWebFilter filter = filter(
                properties(true, 1, 5, 10, false));
        AtomicInteger chainCalls = new AtomicInteger();
        WebFilterChain chain = completingChain(chainCalls);

        verifyComplete(withPrincipal(
                filter.filter(chatExchange(), chain),
                "user-a"));
        MockServerWebExchange rejected = chatExchange();
        verifyComplete(withPrincipal(
                filter.filter(rejected, chain),
                "user-a"));
        verifyComplete(withPrincipal(
                filter.filter(chatExchange(), chain),
                "user-b"));

        assertThat(chainCalls).hasValue(2);
        assertThat(rejected.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void forwardedAddressIsIgnoredUnlessExplicitlyTrusted() {
        AtomicInteger untrustedCalls = new AtomicInteger();
        RateLimitingWebFilter untrusted = filter(
                properties(true, 30, 1, 10, false));
        WebFilterChain untrustedChain = completingChain(
                untrustedCalls);

        verifyComplete(untrusted.filter(
                authExchange(
                        "192.0.2.1",
                        "198.51.100.10"),
                untrustedChain));
        MockServerWebExchange untrustedSecond = authExchange(
                "192.0.2.1",
                "198.51.100.11");
        verifyComplete(untrusted.filter(
                untrustedSecond,
                untrustedChain));

        assertThat(untrustedCalls).hasValue(1);
        assertThat(untrustedSecond.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        AtomicInteger trustedCalls = new AtomicInteger();
        RateLimitingWebFilter trusted = filter(
                properties(true, 30, 1, 10, true));
        WebFilterChain trustedChain = completingChain(
                trustedCalls);

        verifyComplete(trusted.filter(
                authExchange(
                        "192.0.2.1",
                        "198.51.100.10"),
                trustedChain));
        verifyComplete(trusted.filter(
                authExchange(
                        "192.0.2.1",
                        "198.51.100.11"),
                trustedChain));

        assertThat(trustedCalls).hasValue(2);
    }

    @Test
    void disabledAndBypassedTrafficNeverConsumesQuota() {
        AtomicInteger disabledCalls = new AtomicInteger();
        RateLimitingWebFilter disabled = filter(
                properties(false, 1, 1, 1, false));
        WebFilterChain disabledChain = completingChain(
                disabledCalls);

        for (int index = 0; index < 3; index++) {
            verifyComplete(disabled.filter(
                    authExchange("203.0.113.20"),
                    disabledChain));
        }
        assertThat(disabledCalls).hasValue(3);

        AtomicInteger bypassCalls = new AtomicInteger();
        RateLimitingWebFilter enabled = filter(
                properties(true, 1, 1, 1, false));
        WebFilterChain bypassChain = completingChain(
                bypassCalls);
        MockServerWebExchange health = MockServerWebExchange.from(
                MockServerHttpRequest.get(
                        "/actuator/health").build());

        verifyComplete(enabled.filter(health, bypassChain));
        assertThat(bypassCalls).hasValue(1);
        assertThat(health.getResponse().getHeaders()
                .containsKey(RateLimitingWebFilter.LIMIT_HEADER))
                .isFalse();
    }

    private RateLimitingWebFilter filter(
            UltimateProperties.RateLimitingProperties properties) {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                Clock.fixed(
                        Instant.parse("2026-09-15T21:00:00Z"),
                        ZoneOffset.UTC),
                Duration.ofMinutes(1),
                properties.maxTrackedKeys());
        return new RateLimitingWebFilter(limiter, properties);
    }

    private UltimateProperties.RateLimitingProperties properties(
            boolean enabled,
            int chat,
            int auth,
            int admin,
            boolean trustForwardedHeaders) {
        return new UltimateProperties.RateLimitingProperties(
                enabled,
                chat,
                auth,
                admin,
                100,
                trustForwardedHeaders);
    }

    private MockServerWebExchange authExchange(String remoteAddress) {
        return authExchange(remoteAddress, null);
    }

    private MockServerWebExchange authExchange(
            String remoteAddress,
            String forwardedAddress) {
        MockServerHttpRequest.BaseBuilder<?> builder =
                MockServerHttpRequest
                        .post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress(
                                remoteAddress,
                                443));
        if (forwardedAddress != null) {
            builder.header(
                    "X-Forwarded-For",
                    forwardedAddress);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private MockServerWebExchange chatExchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest
                        .post("/api/v1/chat/stream")
                        .remoteAddress(new InetSocketAddress(
                                "203.0.113.30",
                                443))
                        .build());
    }

    private WebFilterChain completingChain(
            AtomicInteger calls) {
        return exchange -> {
            calls.incrementAndGet();
            exchange.getResponse().setStatusCode(
                    HttpStatus.NO_CONTENT);
            return exchange.getResponse().setComplete();
        };
    }

    private Mono<Void> withPrincipal(
            Mono<Void> result,
            String principal) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of());
        return result.contextWrite(
                ReactiveSecurityContextHolder
                        .withAuthentication(authentication));
    }

    private void verifyComplete(Mono<Void> result) {
        StepVerifier.create(result).verifyComplete();
    }
}
