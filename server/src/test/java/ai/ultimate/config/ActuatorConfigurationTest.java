package ai.ultimate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

@DisplayName("Actuator configuration policy")
class ActuatorConfigurationTest {

    @Test
    void actuatorEndpointsAreOptInReadOnlyAndHealthDetailsRequireAdmin() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = Objects.requireNonNull(yaml.getObject());

        assertThat(properties.getProperty("management.endpoints.access.default"))
                .isEqualTo("none");
        assertThat(properties.getProperty("management.endpoints.web.discovery.enabled"))
                .isEqualTo("false");

        assertThat(properties.getProperty("management.endpoint.health.access"))
                .isEqualTo("read-only");
        assertThat(properties.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("when-authorized");
        assertThat(properties.getProperty("management.endpoint.health.show-components"))
                .isEqualTo("when-authorized");
        assertThat(properties.getProperty("management.endpoint.health.roles"))
                .isEqualTo("ADMIN");
        assertThat(properties.getProperty("management.endpoint.health.probes.enabled"))
                .isEqualTo("false");

        assertThat(properties.getProperty("management.endpoint.info.access"))
                .isEqualTo("read-only");
        assertThat(properties.getProperty("management.endpoint.metrics.access"))
                .isEqualTo("read-only");
        assertThat(properties.getProperty("management.endpoint.loggers.access"))
                .isEqualTo("read-only");
        assertThat(properties.getProperty("management.endpoint.prometheus.access"))
                .isEqualTo("read-only");
    }
}
