/* ABOUTME: Exercises payload boundaries, process lifecycle and teardown without LibreOffice. */
package ai.ultimate.tools.builtin;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

class LibreOfficeAutomationToolTest {
    @TempDir Path temporary;
    private static final ToolContext CONTEXT = new ToolContext(Map.of("test-only", true));

    @Test
    void budgetDenialDoesNotCreateFilesOrStartProcess() {
        AtomicBoolean started = new AtomicBoolean();
        var tool = tool((context, cap, count, duration) -> false, (command, directory, environment) -> {
            started.set(true);
            throw new IOException("must not run");
        });
        assertThat(invoke(tool, "hello")).contains("Budget denied");
        assertThat(started).isFalse();
        assertThat(Files.exists(root())).isFalse();
    }

    @Test
    void missingTrustedContextCannotUseEvenAPermissiveTestAdapter() {
        AtomicBoolean reserved = new AtomicBoolean();
        var tool = tool((context, cap, count, duration) -> {
            reserved.set(true);
            return true;
        }, child("success"));
        assertThat(tool.processDocuments(new String[]{"hello"}, "txt", "pdf", "", null))
                .contains("Budget denied");
        assertThat(reserved).isFalse();
    }

    @Test
    void reservationReceivesHardUsdCapAndWholeBatchBeforeAnyExecution() throws Exception {
        AtomicInteger reservations = new AtomicInteger();
        var tool = tool((context, cap, count, duration) -> {
            assertThat(context).isSameAs(CONTEXT);
            assertThat(cap).isEqualByComparingTo(new BigDecimal("150.00"));
            assertThat(count).isEqualTo(2);
            assertThat(duration).isEqualTo(Duration.ofSeconds(60));
            reservations.incrementAndGet();
            return true;
        }, (command, directory, environment) -> {
            assertThat(reservations).hasValue(1);
            return child("success").start(command, directory, environment);
        });
        assertThat(tool.processDocuments(new String[]{"first", "second"}, "txt", "pdf", "", CONTEXT))
                .contains("document-1.pdf", "document-2.pdf").doesNotContain("\"error\"");
        assertEmptyRoot();
    }

    @Test
    void checksEveryPayloadByUtf8BytesBeforeReserving() {
        AtomicBoolean reserved = new AtomicBoolean();
        var tool = tool((context, cap, count, duration) -> {
            reserved.set(true);
            return true;
        }, child("success"));
        String oversized = "€".repeat(1_666_667); // 1,666,667 chars, 5,000,001 UTF-8 bytes.
        assertThat(tool.processDocuments(new String[]{"valid", oversized}, "txt", "pdf", "", CONTEXT))
                .contains("5,000,000 UTF-8 bytes");
        assertThat(reserved).isFalse();
        assertThat(Files.exists(root())).isFalse();
    }

    @Test
    void acceptsExactByteBoundaryAndRejectsOneByteMore() {
        AtomicInteger attempts = new AtomicInteger();
        var tool = tool((context, cap, count, duration) -> {
            attempts.incrementAndGet();
            return false;
        }, child("success"));
        assertThat(invoke(tool, "a".repeat(5_000_000))).contains("Budget denied");
        assertThat(attempts).hasValue(1);
        assertThat(invoke(tool, "a".repeat(5_000_001))).contains("5,000,000 UTF-8 bytes");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void validatesBase64FormatsAndPageRangesBeforeRunning() {
        var tool = tool(allowForTest(), child("success"));
        assertThat(tool.processDocuments(new String[]{"not base64!"}, "docx", "pdf", "", CONTEXT))
                .contains("standard Base64");
        assertThat(tool.processDocuments(new String[]{"aGVsbG8="}, "docx", "pdf", "", CONTEXT))
                .contains("signature");
        assertThat(tool.processDocuments(new String[]{"text"}, "../txt", "pdf", "", CONTEXT))
                .contains("Unsupported");
        assertThat(tool.processDocuments(new String[]{"text"}, "txt", "xlsx", "", CONTEXT))
                .contains("Unsupported");
        assertThat(tool.processDocuments(new String[]{"text"}, "txt", "pdf", "3-1", CONTEXT))
                .contains("ascending");
        assertThat(tool.processDocuments(new String[]{"text"}, "txt", "pdf", "1\" --outdir /tmp", CONTEXT))
                .contains("pdfPages");
        assertThat(tool.processDocuments(new String[]{"text"}, "txt", "docx", "1", CONTEXT))
                .contains("requires PDF");
        assertThat(tool.processDocuments(new String[5], "txt", "pdf", "", CONTEXT))
                .contains("1-4");
        assertThat(Files.exists(root())).isFalse();
    }

    @Test
    void usesArgumentVectorPrivateProfileAndManagedTemporaryDirectories() throws Exception {
        var tool = tool(allowForTest(), (command, directory, environment) -> {
            assertThat(directory).startsWith(root());
            assertThat(command.get(0)).isEqualTo("test-soffice");
            assertThat(command).contains("--headless", "--norestore",
                    "--infilter=Text (encoded):UTF8,LF",
                    "pdf:writer_pdf_Export:{\"PageRange\":{\"type\":\"string\",\"value\":\"1-3,5\"}}");
            String profile = command.get(1);
            assertThat(profile).startsWith("-env:UserInstallation=file:");
            assertThat(Files.readString(directory.resolve("profile/user/registrymodifications.xcu")))
                    .contains("MacroSecurityLevel", "<value>3</value>");
            assertThat(environment.get("TMPDIR")).isEqualTo(directory.resolve("tmp").toString());
            assertThat(environment.get("HOME")).isEqualTo(directory.toString());
            assertThat(Files.readString(directory.resolve("input.txt"))).isEqualTo("héllo $() ;");
            return child("success").start(command, directory, environment);
        });
        assertThat(tool.processDocuments(new String[]{"héllo $() ;"}, "txt", "pdf", "1-3,5", CONTEXT))
                .contains("\"documents\"");
        assertEmptyRoot();
    }

    @Test
    void routesCalcAndDrawFiltersAndDisablesCsvFormulaImport() throws Exception {
        var tool = tool(allowForTest(), (command, directory, environment) -> {
            if (command.getLast().endsWith(".csv")) {
                assertThat(command).contains("xlsx:Calc Office Open XML",
                        "--infilter=Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,0,false");
            } else {
                assertThat(command).contains("--infilter=draw_pdf_import", "pdf:draw_pdf_Export");
            }
            return child("success").start(command, directory, environment);
        });
        assertThat(tool.processDocuments(new String[]{"a,b\n1,2"}, "csv", "xlsx", "", CONTEXT))
                .contains("\"documents\"");
        String pdf = Base64.getEncoder().encodeToString("%PDF-1.7 test".getBytes(StandardCharsets.UTF_8));
        assertThat(tool.processDocuments(new String[]{pdf}, "pdf", "pdf", "", CONTEXT))
                .contains("\"documents\"");
        assertEmptyRoot();
    }

    @Test
    void drainsBothPipesPastCaptureLimitWithoutDeadlock() throws Exception {
        var tool = tool(allowForTest(), child("flood"));
        assertThat(invoke(tool, "hello")).contains("\"documents\"");
        assertEmptyRoot();
    }

    @Test
    void cleansOutputsProfilesAndTemporaryFilesOnFailure() throws Exception {
        for (String mode : List.of("exit-error", "missing", "empty", "oversized", "invalid")) {
            var tool = tool(allowForTest(), child(mode));
            assertThat(invoke(tool, "hello")).contains("\"error\"");
            assertEmptyRoot();
        }
    }

    @Test
    void startupFailureCleansStagedInput() throws Exception {
        var tool = tool(allowForTest(), (command, directory, environment) -> {
            throw new IOException("Executable unavailable");
        });
        assertThat(invoke(tool, "hello")).contains("I/O error");
        assertEmptyRoot();
    }

    @Test
    void timeoutKillsProcessAndCleansScratch() throws Exception {
        var tool = new LibreOfficeAutomationTool(root(), "test-soffice", allowForTest(),
                child("hang"), Duration.ofMillis(250));
        assertThat(invoke(tool, "hello")).contains("runtime limit");
        assertEmptyRoot();
    }

    @Test
    void interruptionIsPreservedAndScratchIsRemoved() throws Exception {
        var tool = tool(allowForTest(), child("hang"));
        Thread.currentThread().interrupt();
        try {
            assertThat(invoke(tool, "hello")).contains("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertEmptyRoot();
    }

    @Test
    void budgetAdapterFailureDoesNotCreateScratch() {
        var tool = tool((context, cap, count, duration) -> {
            throw new IllegalStateException("ledger unavailable");
        }, child("success"));
        assertThat(invoke(tool, "hello")).contains("\"error\"");
        assertThat(Files.exists(root())).isFalse();
    }

    @Test
    void rejectsSymlinkedRootAndPreservesOutsideFiles() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "keep");
        try {
            Files.createSymbolicLink(root(), outside);
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable on this test host");
        }
        var tool = tool(allowForTest(), child("success"));
        assertThat(invoke(tool, "hello")).contains("symbolic links");
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
    }

    @Test
    void rejectsOutputSymlinkAndCleanupDoesNotFollowIt() throws Exception {
        Path sentinel = Files.writeString(temporary.resolve("keep.pdf"), "%PDF-1.7 keep");
        var tool = tool(allowForTest(), (command, directory, environment) -> {
            Files.createSymbolicLink(directory.resolve("out/input.pdf"), sentinel);
            return child("missing").start(command, directory, environment);
        });
        assertThat(invoke(tool, "hello")).contains("regular output");
        assertThat(Files.readString(sentinel)).isEqualTo("%PDF-1.7 keep");
        assertEmptyRoot();
    }

    private Path root() {
        return temporary.resolve("ultimate-managed-workspaces");
    }

    private LibreOfficeAutomationTool tool(LibreOfficeComputeBudget budget,
            LibreOfficeAutomationTool.ProcessExecutor executor) {
        return new LibreOfficeAutomationTool(root(), "test-soffice", budget,
                executor, Duration.ofSeconds(60));
    }

    private static LibreOfficeComputeBudget allowForTest() {
        return (context, cap, count, duration) -> true;
    }

    private static String invoke(LibreOfficeAutomationTool tool, String text) {
        return tool.processDocuments(new String[]{text}, "txt", "pdf", "", CONTEXT);
    }

    private void assertEmptyRoot() throws IOException {
        try (var children = Files.list(root())) {
            assertThat(children).isEmpty();
        }
    }

    private static LibreOfficeAutomationTool.ProcessExecutor child(String mode) {
        return (command, directory, environment) -> {
            int filter = command.indexOf("--convert-to") + 1;
            String extension = command.get(filter).split(":")[0];
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    Child.class.getName(), directory.toString(), extension, mode)
                    .directory(directory.toFile()).start();
        };
    }

    /** A real subprocess fixture, so pipe capacity, timeout and interruption are exercised. */
    public static class Child {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            String extension = args[1];
            String mode = args[2];
            Files.writeString(directory.resolve("tmp/volatile.tmp"), "temporary");
            if ("hang".equals(mode)) {
                Thread.sleep(60_000);
            }
            if ("flood".equals(mode)) {
                byte[] buffer = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 1024; i++) {
                    System.out.write(buffer);
                    System.err.write(buffer);
                }
            }
            if (!"missing".equals(mode)) {
                byte[] bytes = "pdf".equals(extension)
                        ? "%PDF-1.7 fixture".getBytes(StandardCharsets.UTF_8)
                        : new byte[]{'P', 'K', 3, 4, 1};
                if ("empty".equals(mode)) {
                    bytes = new byte[0];
                } else if ("oversized".equals(mode)) {
                    bytes = new byte[10_000_001];
                } else if ("invalid".equals(mode)) {
                    bytes = "invalid".getBytes(StandardCharsets.UTF_8);
                }
                Files.write(directory.resolve("out/input." + extension), bytes);
            }
            if ("exit-error".equals(mode)) {
                System.exit(7);
            }
        }
    }
}
