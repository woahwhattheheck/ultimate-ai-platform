package ai.ultimate.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class UltimatePropertiesBindingTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(BindingConfiguration.class);

    @Test
    void rateLimitingDefaultsBindWhenGroupIsOmitted() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();

            UltimateProperties.RateLimitingProperties rateLimiting =
                    context.getBean(UltimateProperties.class)
                            .security()
                            .rateLimiting();

            assertThat(rateLimiting.enabled()).isTrue();
            assertThat(rateLimiting.chatRequestsPerMinute()).isEqualTo(30);
            assertThat(rateLimiting.authAttemptsPerMinute()).isEqualTo(5);
            assertThat(rateLimiting.adminRequestsPerMinute()).isEqualTo(10);
            assertThat(rateLimiting.maxTrackedKeys()).isEqualTo(10000);
            assertThat(rateLimiting.trustForwardedHeaders()).isFalse();
        });
    }

    @Test
    void rateLimitingPartialConfigurationKeepsOmittedDefaults() {
        contextRunner
                .withPropertyValues(
                        "ultimate.security.rate-limiting.enabled=false",
                        "ultimate.security.rate-limiting.chat-requests-per-minute=17")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();

                    UltimateProperties.RateLimitingProperties rateLimiting =
                            context.getBean(UltimateProperties.class)
                                    .security()
                                    .rateLimiting();

                    assertThat(rateLimiting.enabled()).isFalse();
                    assertThat(rateLimiting.chatRequestsPerMinute()).isEqualTo(17);
                    assertThat(rateLimiting.authAttemptsPerMinute()).isEqualTo(5);
                    assertThat(rateLimiting.adminRequestsPerMinute()).isEqualTo(10);
                    assertThat(rateLimiting.maxTrackedKeys()).isEqualTo(10000);
                    assertThat(rateLimiting.trustForwardedHeaders()).isFalse();
                });
    }

    @Test
    void rateLimitingFullConfigurationBindsAllFields() {
        contextRunner
                .withPropertyValues(
                        "ultimate.security.rate-limiting.enabled=false",
                        "ultimate.security.rate-limiting.chat-requests-per-minute=71",
                        "ultimate.security.rate-limiting.auth-attempts-per-minute=13",
                        "ultimate.security.rate-limiting.admin-requests-per-minute=19",
                        "ultimate.security.rate-limiting.max-tracked-keys=321",
                        "ultimate.security.rate-limiting.trust-forwarded-headers=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();

                    UltimateProperties.RateLimitingProperties rateLimiting =
                            context.getBean(UltimateProperties.class)
                                    .security()
                                    .rateLimiting();

                    assertThat(rateLimiting.enabled()).isFalse();
                    assertThat(rateLimiting.chatRequestsPerMinute()).isEqualTo(71);
                    assertThat(rateLimiting.authAttemptsPerMinute()).isEqualTo(13);
                    assertThat(rateLimiting.adminRequestsPerMinute()).isEqualTo(19);
                    assertThat(rateLimiting.maxTrackedKeys()).isEqualTo(321);
                    assertThat(rateLimiting.trustForwardedHeaders()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UltimateProperties.class)
    static class BindingConfiguration {
    }
}
