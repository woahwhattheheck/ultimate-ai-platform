package ai.ultimate.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ai.ultimate.security.jwt.JwtAuthenticationFilter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

@WebFluxTest(controllers = SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, SecurityConfigTest.TestController.class})
@DisplayName("SecurityConfig actuator authorization")
class SecurityConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    private WebTestClient webTestClient;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void configureSecurityTestClient() {
        when(jwtAuthenticationFilter.filter(any(), any()))
                .thenAnswer(invocation -> {
                    ServerWebExchange exchange = invocation.getArgument(0);
                    WebFilterChain chain = invocation.getArgument(1);
                    return chain.filter(exchange);
                });

        webTestClient = WebTestClient.bindToApplicationContext(applicationContext)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void anonymousHealthRequestIsAllowed() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void anonymousStandardHealthProbesAreAllowed() {
        webTestClient.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        webTestClient.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    void anonymousNamedHealthComponentRequiresAuthentication() {
        webTestClient.get().uri("/actuator/health/db").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void anonymousActuatorRequestRequiresAuthentication() {
        webTestClient.get().uri("/actuator/metrics").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void authenticatedNonAdminCannotReadActuator() {
        webTestClient
                .mutateWith(mockUser().roles("USER"))
                .get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void authenticatedNonAdminCannotReadNamedHealthComponents() {
        webTestClient
                .mutateWith(mockUser().roles("USER"))
                .get()
                .uri("/actuator/health/db")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void administratorCanReadActuator() {
        webTestClient
                .mutateWith(mockUser().roles("ADMIN"))
                .get()
                .uri("/actuator/metrics")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void authenticatedNonAdminCanUseApplicationEndpoints() {
        webTestClient
                .mutateWith(mockUser().roles("USER"))
                .get()
                .uri("/api/v1/private")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    void swaggerRemainsPublic() {
        webTestClient.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }

    @RestController
    public static class TestController {

        @GetMapping({
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/health/db",
                "/actuator/metrics",
                "/api/v1/private",
                "/swagger-ui/index.html"
        })
        public Map<String, String> ok() {
            return Map.of("status", "ok");
        }
    }
}
