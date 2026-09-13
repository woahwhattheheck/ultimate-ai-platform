package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CodiumAutomationTool container identity")
class CodiumAutomationToolContainerIdentityTest {

    private static final String PREFIX = "ultimate_secure_pipeline_";

    @Test
    @DisplayName("uses collision-resistant UUID v4 container names")
    void shouldGenerateUniqueUuidV4ContainerNames() {
        Set<String> names = new HashSet<>();

        for (int i = 0; i < 4_096; i++) {
            String name = CodiumAutomationTool.newContainerName();
            assertThat(name).startsWith(PREFIX);

            UUID id = UUID.fromString(name.substring(PREFIX.length()));
            assertThat(id.version()).isEqualTo(4);
            assertThat(names.add(name)).isTrue();
        }

        assertThat(names).hasSize(4_096);
    }
}
