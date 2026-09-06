/* ABOUTME: Bounded headless document conversion with ephemeral managed storage. */
package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LibreOfficeAutomationTool implements UltimateTool {
    static final int MAX_PAYLOAD_BYTES = 5_000_000;
    static final int MAX_OUTPUT_BYTES = 10_000_000;
    static final int MAX_BATCH = 4;
    static final BigDecimal MAX_SESSION_USD = new BigDecimal("150.00");
    static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_DIAGNOSTIC_BYTES = 16_384;
    private static final Set<String> INPUT_FORMATS = Set.of("txt", "csv", "docx", "xlsx", "pdf");

    private final Path managedRoot;
    private final String executable;
    private final LibreOfficeComputeBudget budget;
    private final ProcessExecutor executor;
    private final Duration timeout;

    @Autowired
    public LibreOfficeAutomationTool(ObjectProvider<LibreOfficeComputeBudget> budgets) {
        this(Path.of(System.getProperty("user.home"), "ultimate-managed-workspaces"),
                "soffice", budgets.getIfAvailable(() -> (context, cap, count, duration) -> false),
                LibreOfficeAutomationTool::startProcess, PROCESS_TIMEOUT);
    }

    LibreOfficeAutomationTool(Path managedRoot, String executable,
            LibreOfficeComputeBudget budget, ProcessExecutor executor, Duration timeout) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.executable = executable;
        this.budget = budget;
        this.executor = executor;
        this.timeout = timeout;
    }

    @Tool(description = "Generate DOCX/PDF from UTF-8 text or XLSX/PDF from UTF-8 CSV, "
            + "convert DOCX/XLSX to PDF, or export selected PDF pages. Use for document "
            + "creation and PDF page extraction. payloads contains 1-4 documents: plain "
            + "text for txt/csv; standard Base64 for docx/xlsx/pdf. Each encoded payload "
            + "must be at most 5,000,000 UTF-8 bytes. Input/output pairs: txt/docx -> "
            + "docx or pdf; csv/xlsx -> xlsx or pdf; pdf -> pdf. pdfPages is empty for "
            + "all pages, or e.g. 1-3,5. Returns JSON with Base64 documents or an error. "
            + "All scratch files are removed before return. Requires a server-provided "
            + "compute-budget authorization; execution is denied if unavailable.")
    public String processDocuments(
            @ToolParam(description = "1-4 input documents, UTF-8 text or standard Base64 as described")
            String[] payloads,
            @ToolParam(description = "txt, csv, docx, xlsx, or pdf") String inputFormat,
            @ToolParam(description = "docx, xlsx, or pdf, compatible with inputFormat") String outputFormat,
            @ToolParam(description = "PDF page selection such as 1-3,5; empty for all pages")
            String pdfPages,
            ToolContext toolContext) {
        Path runtime = null;
        String result;
        try {
            List<byte[]> inputs = validate(payloads, inputFormat, outputFormat, pdfPages);
            if (toolContext == null || !budget.reserve(
                    toolContext, MAX_SESSION_USD, inputs.size(), timeout)) {
                return error("Budget denied: an authoritative session-window USD reservation is required.");
            }
            runtime = createRuntime();
            List<String> outputs = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                Path operation = Files.createDirectory(runtime.resolve("document-" + (i + 1)));
                Path profile = Files.createDirectory(operation.resolve("profile"));
                configureProfile(profile);
                Path temporary = Files.createDirectory(operation.resolve("tmp"));
                Path outputDirectory = Files.createDirectory(operation.resolve("out"));
                Path input = operation.resolve("input." + inputFormat);
                Files.write(input, inputs.get(i));
                run(command(input, profile, outputDirectory, inputFormat, outputFormat, pdfPages),
                        operation, temporary);
                Path output = outputDirectory.resolve("input." + outputFormat);
                if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                        || !output.toRealPath().startsWith(runtime)) {
                    throw new DocumentException("LibreOffice did not produce a regular output file.");
                }
                byte[] bytes;
                try (InputStream stream = Files.newInputStream(output)) {
                    bytes = stream.readNBytes(MAX_OUTPUT_BYTES + 1);
                }
                if (bytes.length == 0 || bytes.length > MAX_OUTPUT_BYTES) {
                    throw new DocumentException("Output is empty or exceeds the 10,000,000 byte limit.");
                }
                if (!hasSignature(bytes, outputFormat)) {
                    throw new DocumentException("LibreOffice output does not match the requested format.");
                }
                outputs.add("{\"name\":\"document-" + (i + 1) + "." + outputFormat
                        + "\",\"encoding\":\"base64\",\"data\":\""
                        + Base64.getEncoder().encodeToString(bytes) + "\"}");
            }
            result = "{\"documents\":[" + String.join(",", outputs) + "]}";
        } catch (IllegalArgumentException e) {
            result = error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = error("Document processing was interrupted.");
        } catch (IOException e) {
            result = error(e instanceof DocumentException ? e.getMessage() : "Document processing failed: I/O error.");
        } catch (RuntimeException e) {
            result = error("Document processing failed; no files are returned.");
        } finally {
            if (runtime != null) {
                try {
                    cleanup(runtime);
                } catch (IOException | RuntimeException e) {
                    // Never return a successful artifact when volatile teardown failed.
                    result = error("Document cleanup failed; operator intervention is required.");
                }
            }
        }
        return result;
    }

    private List<byte[]> validate(String[] payloads, String inputFormat,
            String outputFormat, String pages) {
        if (payloads == null || payloads.length == 0 || payloads.length > MAX_BATCH) {
            throw new IllegalArgumentException("Provide 1-4 document payloads.");
        }
        if (inputFormat == null || !INPUT_FORMATS.contains(inputFormat)
                || outputFormat == null || !validPair(inputFormat, outputFormat)) {
            throw new IllegalArgumentException("Unsupported input/output format pair.");
        }
        if (pages == null || pages.length() > 128
                || (!pages.isEmpty() && !pages.matches("[1-9][0-9]{0,5}(-[1-9][0-9]{0,5})?(,[1-9][0-9]{0,5}(-[1-9][0-9]{0,5})?)*"))) {
            throw new IllegalArgumentException("pdfPages must be empty or a page list such as 1-3,5.");
        }
        if (!pages.isEmpty() && !"pdf".equals(outputFormat)) {
            throw new IllegalArgumentException("pdfPages requires PDF output.");
        }
        for (String range : pages.isEmpty() ? new String[0] : pages.split(",")) {
            String[] endpoints = range.split("-");
            if (endpoints.length == 2 && Integer.parseInt(endpoints[0]) > Integer.parseInt(endpoints[1])) {
                throw new IllegalArgumentException("PDF page ranges must be ascending.");
            }
        }
        List<byte[]> result = new ArrayList<>();
        for (String payload : payloads) {
            if (payload == null || payload.isEmpty() || payload.length() > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Each payload must contain 1-5,000,000 UTF-8 bytes.");
            }
            byte[] utf8 = payload.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Each payload must contain 1-5,000,000 UTF-8 bytes.");
            }
            byte[] data;
            if ("txt".equals(inputFormat) || "csv".equals(inputFormat)) {
                data = utf8;
            } else {
                try {
                    data = Base64.getDecoder().decode(payload);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Binary documents must use standard Base64.");
                }
                if (!hasSignature(data, inputFormat)) {
                    throw new IllegalArgumentException("Binary document signature does not match inputFormat.");
                }
            }
            result.add(data);
        }
        return result;
    }

    private static boolean validPair(String input, String output) {
        return switch (input) {
            case "txt", "docx" -> "docx".equals(output) || "pdf".equals(output);
            case "csv", "xlsx" -> "xlsx".equals(output) || "pdf".equals(output);
            case "pdf" -> "pdf".equals(output);
            default -> false;
        };
    }

    private static boolean hasSignature(byte[] bytes, String format) {
        if ("pdf".equals(format)) {
            return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P'
                    && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
        }
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 3 && bytes[3] == 4;
    }

    private Path createRuntime() throws IOException {
        // The configured root and its existing ancestors may not be symlinks.
        for (Path current = managedRoot; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new DocumentException("Managed workspace root must not traverse symbolic links.");
            }
        }
        Files.createDirectories(managedRoot);
        Path canonicalRoot = managedRoot.toRealPath();
        if (!canonicalRoot.equals(managedRoot)) {
            throw new DocumentException("Managed workspace root must resolve to its configured path.");
        }
        Path runtime;
        if (Files.getFileStore(canonicalRoot).supportsFileAttributeView("posix")) {
            runtime = Files.createTempDirectory(canonicalRoot, ".ultimate-office-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            runtime = Files.createTempDirectory(canonicalRoot, ".ultimate-office-");
        }
        return runtime.toRealPath();
    }

    private static void configureProfile(Path profile) throws IOException {
        Path user = Files.createDirectory(profile.resolve("user"));
        Files.writeString(user.resolve("registrymodifications.xcu"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <oor:items xmlns:oor="http://openoffice.org/2001/registry">
                  <item oor:path="/org.openoffice.Office.Common/Security/Scripting">
                    <prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop>
                  </item>
                </oor:items>
                """, StandardCharsets.UTF_8);
    }

    private List<String> command(Path input, Path profile, Path outputDirectory,
            String inputFormat, String outputFormat, String pages) {
        List<String> command = new ArrayList<>(List.of(executable,
                "-env:UserInstallation=" + profile.toUri().toASCIIString(),
                "--headless", "--nologo", "--nodefault", "--norestore"));
        if ("txt".equals(inputFormat)) {
            command.add("--infilter=Text (encoded):UTF8,LF");
        } else if ("csv".equals(inputFormat)) {
            // Comma separator, quote delimiter, UTF-8; formulas disabled (token 13).
            command.add("--infilter=Text - txt - csv (StarCalc):44,34,76,1,,0,false,true,false,false,false,0,false");
        } else if ("pdf".equals(inputFormat)) {
            command.add("--infilter=draw_pdf_import");
        }
        String filter;
        if ("docx".equals(outputFormat)) {
            filter = "docx:Office Open XML Text";
        } else if ("xlsx".equals(outputFormat)) {
            filter = "xlsx:Calc Office Open XML";
        } else {
            String pdfFilter = switch (inputFormat) {
                case "csv", "xlsx" -> "calc_pdf_Export";
                case "pdf" -> "draw_pdf_Export";
                default -> "writer_pdf_Export";
            };
            filter = "pdf:" + pdfFilter;
            if (!pages.isEmpty()) {
                filter += ":{\"PageRange\":{\"type\":\"string\",\"value\":\"" + pages + "\"}}";
            }
        }
        command.addAll(List.of("--convert-to", filter, "--outdir", outputDirectory.toString(), input.toString()));
        return List.copyOf(command);
    }

    private void run(List<String> command, Path directory, Path temporary)
            throws IOException, InterruptedException {
        Process process = executor.start(command, directory, Map.of(
                "TMPDIR", temporary.toString(), "TMP", temporary.toString(),
                "TEMP", temporary.toString(), "HOME", directory.toString()));
        ExecutorService drainers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ultimate-office-stream");
            thread.setDaemon(true);
            return thread;
        });
        try {
            // Separate drainers start before waiting; keep draining after capture is full.
            Future<byte[]> stdout = drainers.submit(() -> drain(process.getInputStream()));
            Future<byte[]> stderr = drainers.submit(() -> drain(process.getErrorStream()));
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new DocumentException("LibreOffice exceeded its runtime limit.");
            }
            awaitDrain(stdout);
            awaitDrain(stderr);
            if (process.exitValue() != 0) {
                throw new DocumentException("LibreOffice exited with code " + process.exitValue() + ".");
            }
        } finally {
            terminate(process);
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
            drainers.shutdownNow();
        }
    }

    private static byte[] drain(InputStream stream) throws IOException {
        try (stream; var captured = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) != -1;) {
                int kept = Math.min(count, MAX_DIAGNOSTIC_BYTES - captured.size());
                if (kept > 0) {
                    captured.write(buffer, 0, kept);
                }
            }
            return captured.toByteArray();
        }
    }

    private static void awaitDrain(Future<byte[]> future) throws IOException, InterruptedException {
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new DocumentException("LibreOffice output streams did not close cleanly.", e);
        }
    }

    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            List<ProcessHandle> children = process.descendants().toList();
            children.forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                process.destroyForcibly();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void close(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best effort after process termination; runtime deletion is checked separately.
        }
    }

    private static Process startProcess(List<String> command, Path directory,
            Map<String, String> environment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().putAll(environment);
        return builder.start();
    }

    private static void cleanup(Path runtime) throws IOException {
        // walkFileTree does not follow symbolic links.
        Files.walkFileTree(runtime, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String error(String message) {
        String safe = message == null ? "Document processing failed." : message;
        safe = safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ").replace("\t", " ");
        return "{\"error\":\"" + safe + "\"}";
    }

    private static final class DocumentException extends IOException {
        private DocumentException(String message) {
            super(message);
        }

        private DocumentException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    interface ProcessExecutor {
        Process start(List<String> command, Path directory, Map<String, String> environment) throws IOException;
    }
}
