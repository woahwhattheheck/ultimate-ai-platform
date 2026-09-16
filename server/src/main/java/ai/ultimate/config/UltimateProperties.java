package ai.ultimate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "ultimate")
public record UltimateProperties(

        @DefaultValue("0.1.0-SNAPSHOT")
        String version,

        @DefaultValue
        SecurityProperties security,

        @DefaultValue
        AiProperties ai,

        @DefaultValue
        CliProperties cli,

        @DefaultValue
        ObservabilityProperties observability,

        @DefaultValue
        VoiceProperties voice

) {

    public record SecurityProperties(
            @DefaultValue
            JwtProperties jwt,
            @DefaultValue
            RateLimitingProperties rateLimiting,
            @DefaultValue
            Argon2Properties argon2
    ) {}

    public record JwtProperties(
            @DefaultValue("dev-secret-key-change-in-production-min-32-chars")
            String secret,
            @DefaultValue("15m")
            Duration accessTokenExpiry,
            @DefaultValue("7d")
            Duration refreshTokenExpiry,
            @DefaultValue("Ultimate-ai-platform")
            String issuer
    ) {}

    public record RateLimitingProperties(
            @DefaultValue("true")
            boolean enabled,
            @DefaultValue("30")
            int chatRequestsPerMinute,
            @DefaultValue("5")
            int authAttemptsPerMinute,
            @DefaultValue("10")
            int adminRequestsPerMinute,
            @DefaultValue("10000")
            int maxTrackedKeys,
            @DefaultValue("false")
            boolean trustForwardedHeaders
    ) {
        /**
         * Compatibility constructor for existing in-process callers that use
         * the original four-field rate-limit configuration.
         */
        public RateLimitingProperties(
                boolean enabled,
                int chatRequestsPerMinute,
                int authAttemptsPerMinute,
                int adminRequestsPerMinute) {
            this(
                    enabled,
                    chatRequestsPerMinute,
                    authAttemptsPerMinute,
                    adminRequestsPerMinute,
                    10000,
                    false);
        }
    }

    public record Argon2Properties(
            @DefaultValue("65536")
            int memory,
            @DefaultValue("3")
            int iterations,
            @DefaultValue("1")
            int parallelism,
            @DefaultValue("32")
            int hashLength,
            @DefaultValue("16")
            int saltLength
    ) {}

    public record AiProperties(
            @DefaultValue("ollama")
            String primaryProvider,
            @DefaultValue("gemini")
            String fallbackProvider,
            @DefaultValue("3s")
            Duration providerHealthCheckTimeout,
            @DefaultValue
            StreamingProperties streaming,
            @DefaultValue
            ContextProperties context,
            @DefaultValue
            GuardrailProperties guardrails
    ) {}

    public record StreamingProperties(
            @DefaultValue("120s")
            Duration timeout,
            @DefaultValue("1")
            int bufferSize
    ) {}

    public record ContextProperties(
            @DefaultValue("8000")
            int maxTokens,
            @DefaultValue("2000")
            int reserveForResponse,
            @DefaultValue("25")
            int summarizeThreshold,
            @DefaultValue("20")
            int summarizeBatchSize,
            @DefaultValue("6")
            int minMessagesToKeep
    ) {}

    public record GuardrailProperties(
            @DefaultValue("10000")
            int maxInputLength,
            @DefaultValue("true")
            boolean injectionDetectionEnabled
    ) {}

    public record CliProperties(
            @DefaultValue("${user.home}/.Ultimate")
            String configDir,
            @DefaultValue("${user.home}/.Ultimate/logs")
            String logDir,
            @DefaultValue("${user.home}/.Ultimate/auth.json")
            String tokensFile,
            @DefaultValue("${user.home}/.Ultimate/session.json")
            String sessionFile,
            @DefaultValue("${user.home}/.Ultimate/preferences.json")
            String preferencesFile
    ) {}

    public record ObservabilityProperties(
            @DefaultValue("true")
            boolean logAiRequests,
            @DefaultValue("true")
            boolean logTokenUsage,
            @DefaultValue("true")
            boolean logProviderSelection,
            @DefaultValue("5m")
            Duration performanceSummaryInterval
    ) {}

    public record VoiceProperties(

            @DefaultValue
            WhisperProperties whisper,

            @DefaultValue
            TtsProperties tts

    ) {}

    public record WhisperProperties(

            @DefaultValue("https://api.groq.com/openai/v1")
            String baseUrl,

            @DefaultValue("")
            String apiKey,

            @DefaultValue("whisper-large-v3-turbo")
            String model

    ) {}

    public record TtsProperties(

            @DefaultValue("true")
            boolean enabled,

            // Voice name — OS specific
            // Empty string = use system default voice
            // Windows: "Microsoft Zira Desktop"
            // macOS:   "Samantha"
            // Linux:   "en+f3"
            @DefaultValue("")
            String voice,

            // Speed multiplier
            // 1.0 = normal, 1.5 = faster, 0.8 = slower
            @DefaultValue("1.0")
            double speed

    ) {}
}
