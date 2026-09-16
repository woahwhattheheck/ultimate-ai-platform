package ai.ultimate.security.ratelimit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/auth/login, AUTH",
            "POST, /api/v1/chat/stream, CHAT",
            "POST, /api/v1/agents, CHAT",
            "POST, /api/v1/voice/transcribe, CHAT",
            "POST, /api/v1/memories, CHAT",
            "POST, /api/v1/documents, CHAT",
            "PATCH, /api/v1/settings/voice, ADMIN",
            "GET, /api/v1/admin/audit, ADMIN",
            "GET, /api/v1/memories, NONE",
            "GET, /actuator/health, NONE",
            "OPTIONS, /api/v1/auth/login, NONE",
            "POST, /api/v1/chatty, NONE"
    })
    void classifiesRoutesWithBoundarySafePrefixes(
            String method,
            String path,
            String expected) {
        RateLimitPolicy policy = RateLimitPolicy.classify(
                org.springframework.http.HttpMethod.valueOf(method),
                path);

        if ("NONE".equals(expected)) {
            assertThat(policy).isNull();
        } else {
            assertThat(policy).hasToString(expected);
        }
    }
}
