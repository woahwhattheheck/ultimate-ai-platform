/* ABOUTME: Checks automatic registration, model schema and fail-closed default wiring. */
package ai.ultimate.tools;

import ai.ultimate.tools.builtin.LibreOfficeAutomationTool;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class LibreOfficeAutomationToolRegistryTest {
    @Test
    void autoDiscoversToolAndKeepsBudgetContextOutOfModelSchema() {
        try (var context = new AnnotationConfigApplicationContext(
                LibreOfficeAutomationTool.class, ToolRegistry.class)) {
            var tool = context.getBean(LibreOfficeAutomationTool.class);
            assertThat(context.getBean(ToolRegistry.class).getAll()).contains(tool);
            var callbacks = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks();
            assertThat(callbacks).hasSize(1);
            String schema = callbacks[0].getToolDefinition().inputSchema();
            assertThat(schema).contains("payloads", "inputFormat", "outputFormat", "pdfPages")
                    .doesNotContain("toolContext", "sessionId", "cost", "budget");
            assertThat(tool.processDocuments(new String[]{"hello"}, "txt", "pdf", "",
                    new ToolContext(Map.of()))).contains("Budget denied");
        }
    }
}
