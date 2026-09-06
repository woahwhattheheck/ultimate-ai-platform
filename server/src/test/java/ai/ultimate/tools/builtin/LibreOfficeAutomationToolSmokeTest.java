/* ABOUTME: Real LibreOffice smoke coverage, explicitly enabled in the dedicated cloud job. */
package ai.ultimate.tools.builtin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "LIBREOFFICE_SMOKE", matches = "true")
class LibreOfficeAutomationToolSmokeTest {
    @TempDir Path temporary;

    @Test
    void generatesOfficeDocumentsAndConvertsThemToPdfWithRealLibreOffice() throws Exception {
        var tool = tool();
        byte[] docx = call(tool, "Quarterly report\nCafé Ω\nRevenue: 42", "txt", "docx", "");
        assertThat(zipEntry(docx, "word/document.xml")).contains("Quarterly report", "Café", "42");
        byte[] documentPdf = call(tool, Base64.getEncoder().encodeToString(docx), "docx", "pdf", "");
        assertThat(pdfText(documentPdf)).contains("Quarterly report", "Café", "42");

        byte[] xlsx = call(tool, "Name,Value\nRevenue,42\nFormula,=1+1", "csv", "xlsx", "");
        assertThat(zipEntry(xlsx, "xl/sharedStrings.xml")).contains("Revenue", "=1+1");
        assertThat(zipEntry(xlsx, "xl/worksheets/sheet1.xml")).doesNotContain("<f>");
        byte[] spreadsheetPdf = call(tool, Base64.getEncoder().encodeToString(xlsx), "xlsx", "pdf", "");
        assertThat(pdfText(spreadsheetPdf)).contains("Revenue", "42");
        assertNoScratch();
    }

    @Test
    void extractsOnePageFromARealMultipagePdf() throws Exception {
        var tool = tool();
        byte[] pdf = call(tool, "A line in the report\n".repeat(200), "txt", "pdf", "");
        String info = pdfInfo(pdf);
        assertThat(info).doesNotContainPattern("Pages:\\s+1\\s");
        byte[] selected = call(tool, Base64.getEncoder().encodeToString(pdf), "pdf", "pdf", "1");
        assertThat(pdfInfo(selected)).containsPattern("Pages:\\s+1\\s");
        assertThat(pdfText(selected)).contains("A line in the report");
        assertNoScratch();
    }

    private LibreOfficeAutomationTool tool() {
        // This fixture authorizes only these test operations. It is not a USD billing adapter.
        return new LibreOfficeAutomationTool(temporary.resolve("ultimate-managed-workspaces"),
                "soffice", (context, cap, count, duration) -> true,
                (command, directory, environment) -> {
                    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
                    builder.environment().putAll(environment);
                    return builder.start();
                }, Duration.ofSeconds(60));
    }

    private static byte[] call(LibreOfficeAutomationTool tool, String payload,
            String input, String output, String pages) {
        String result = tool.processDocuments(new String[]{payload}, input, output, pages,
                new ToolContext(Map.of("smoke-test-only", true)));
        assertThat(result).doesNotContain("\"error\"").startsWith("{\"documents\":[");
        String marker = "\"data\":\"";
        int start = result.indexOf(marker) + marker.length();
        return Base64.getDecoder().decode(result.substring(start, result.indexOf('"', start)));
    }

    private static String zipEntry(byte[] archive, String name) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (name.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("Missing OOXML entry: " + name);
    }

    private static String pdfText(byte[] pdf) throws Exception {
        return inspectPdf(pdf, "pdftotext", "-", "-");
    }

    private static String pdfInfo(byte[] pdf) throws Exception {
        return inspectPdf(pdf, "pdfinfo", "-");
    }

    private static String inspectPdf(byte[] pdf, String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getOutputStream().write(pdf);
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).isZero();
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    private void assertNoScratch() throws IOException {
        try (var paths = Files.list(temporary.resolve("ultimate-managed-workspaces"))) {
            assertThat(paths).isEmpty();
        }
    }
}
