package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolTest {
    @TempDir
    Path temporary;

    private Path workspace() throws IOException {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png",
                workspace.resolve("input.png").toFile());
        return workspace;
    }

    private String process(ImageProcessingTool tool, Path workspace, List<String> inputs) {
        return tool.processImages(workspace.toString(), inputs, "result", "webp",
                20, 20, true, 110, 90, 85, "Example watermark");
    }

    private ImageProcessingTool tool(ImageProcessingTool.CommandRunner runner) {
        return new ImageProcessingTool(temporary, "magick", runner);
    }

    private static Path outputFile(List<String> command) {
        String argument = command.getLast();
        return Path.of(argument.substring(argument.indexOf(':') + 1));
    }

    private static byte[] webp(String marker) {
        byte[] suffix = marker.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[12 + suffix.length];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        System.arraycopy(suffix, 0, bytes, 12, suffix.length);
        return bytes;
    }

    private static byte[] firstArtifact(String result) {
        String marker = "\"data\":\"";
        int start = result.indexOf(marker);
        assertTrue(start >= 0, result);
        start += marker.length();
        int end = result.indexOf('"', start);
        assertTrue(end > start, result);
        return Base64.getDecoder().decode(result.substring(start, end));
    }

    private void assertNoRuntime() throws IOException {
        try (var paths = Files.list(temporary)) {
            assertFalse(paths.anyMatch(path ->
                    path.getFileName().toString().startsWith(".ultimate-image-")));
        }
    }

    @Test
    void returnsBoundedBase64ArtifactsAndPurgesEveryGeneratedFile() throws Exception {
        Path workspace = workspace();
        List<List<String>> commands = new ArrayList<>();
        ImageProcessingTool tool = tool((command, runtime, timeout) -> {
            commands.add(command);
            assertEquals(Duration.ofSeconds(30), timeout);
            assertTrue(runtime.startsWith(temporary));
            Files.write(outputFile(command), webp("processed"));
            return new ImageProcessingTool.Execution(0, false);
        });

        String result = process(tool, workspace, List.of("input.png"));

        assertTrue(result.startsWith("{\"images\":["), result);
        assertTrue(result.contains("\"name\":\"result-1.webp\""), result);
        assertTrue(result.contains("\"encoding\":\"base64\""), result);
        assertEquals("RIFF\u0000\u0000\u0000\u0000WEBPprocessed",
                new String(firstArtifact(result), StandardCharsets.ISO_8859_1));
        assertEquals(1, commands.size());
        assertTrue(commands.getFirst().containsAll(List.of(
                "-resize", "20x20^", "-gravity", "center", "-extent", "20x20",
                "-modulate", "110,90,100", "-strip", "-quality", "85")));
        assertTrue(commands.getFirst().stream()
                .anyMatch(value -> value.startsWith("PNG:") && value.endsWith("[0]")));
        assertTrue(Files.exists(workspace.resolve("input.png")));
        try (var files = Files.list(workspace)) {
            assertEquals(List.of("input.png"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
        assertNoRuntime();
    }

    @Test
    void rejectsTraversalAbsolutePathsAndOutsideSymlinksBeforeExecution() throws Exception {
        Path workspace = workspace();
        Path outside = Files.copy(workspace.resolve("input.png"), temporary.resolve("outside.png"));
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, runtime, timeout) -> {
            calls.incrementAndGet();
            return new ImageProcessingTool.Execution(0, false);
        });

        assertTrue(process(tool, workspace, List.of("../outside.png")).contains("escapes"));
        assertTrue(process(tool, workspace, List.of(outside.toString())).contains("escapes"));

        try {
            Files.createSymbolicLink(workspace.resolve("link.png"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links: " + e.getMessage());
        }
        assertTrue(process(tool, workspace, List.of("link.png")).contains("outside"));
        assertEquals(0, calls.get());
        assertNoRuntime();
    }

    @Test
    void rejectsDisguisedImageScriptsAndInvalidArguments() throws Exception {
        Path workspace = workspace();
        Files.writeString(workspace.resolve("script.png"),
                "<image><read filename=\"/etc/passwd\"/></image>");
        ImageProcessingTool tool = tool((command, runtime, timeout) -> {
            throw new AssertionError("Invalid requests must not start ImageMagick");
        });

        assertTrue(process(tool, workspace, List.of("script.png"))
                .contains("Only PNG, JPEG and WebP"));
        assertTrue(process(tool, workspace, null).contains("one to four"));
        assertTrue(tool.processImages(workspace.toString(), List.of("input.png"), "result",
                "pdf", 0, 0, false, 100, 100, 80, "").contains("format"));
        assertTrue(tool.processImages(workspace.toString(), List.of("input.png"), "result",
                "png", 0, 10, false, 100, 100, 80, "").contains("Dimensions"));
        for (String watermark : new String[]{"%[fx:1+1]", "@secret", "line\nline", "\\n", null}) {
            assertTrue(tool.processImages(workspace.toString(), List.of("input.png"), "result",
                    "png", 10, 10, false, 100, 100, 80, watermark).contains("\"error\""));
        }
        assertNoRuntime();
    }

    @Test
    void rejectsDuplicateAndOversizedInputs() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool tool = tool((command, runtime, timeout) -> {
            throw new AssertionError("Invalid requests must not start ImageMagick");
        });

        assertTrue(process(tool, workspace, List.of("input.png", "./input.png"))
                .contains("distinct"));
        try (RandomAccessFile file =
                     new RandomAccessFile(workspace.resolve("large.png").toFile(), "rw")) {
            file.setLength(ImageProcessingTool.MAX_INPUT_BYTES + 1);
        }
        assertTrue(process(tool, workspace, List.of("large.png")).contains("20 MiB"));
        assertNoRuntime();
    }

    @Test
    void neverReturnsPartialArtifactsWhenAnyBatchMemberFails() throws Exception {
        Path workspace = workspace();
        Files.copy(workspace.resolve("input.png"), workspace.resolve("second.png"));
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, runtime, timeout) -> {
            Files.write(outputFile(command), webp("processed"));
            return new ImageProcessingTool.Execution(
                    calls.incrementAndGet() == 2 ? 7 : 0, false);
        });

        String result = process(tool, workspace, List.of("input.png", "second.png"));

        assertTrue(result.contains("\"error\""), result);
        assertFalse(result.contains("\"images\""), result);
        assertTrue(result.contains("exited with code 7"), result);
        assertNoRuntime();
    }

    @Test
    void rejectsEmptyWrongFormatOversizedAndOverAggregateOutputs() throws Exception {
        Path workspace = workspace();

        ImageProcessingTool empty = tool((command, runtime, timeout) ->
                new ImageProcessingTool.Execution(0, false));
        assertTrue(process(empty, workspace, List.of("input.png")).contains("empty"));

        ImageProcessingTool wrong = tool((command, runtime, timeout) -> {
            Files.writeString(outputFile(command), "not a webp");
            return new ImageProcessingTool.Execution(0, false);
        });
        assertTrue(process(wrong, workspace, List.of("input.png"))
                .contains("does not match"));

        ImageProcessingTool oversized = tool((command, runtime, timeout) -> {
            Path output = outputFile(command);
            try (RandomAccessFile file = new RandomAccessFile(output.toFile(), "rw")) {
                file.write(webp("x"));
                file.setLength(ImageProcessingTool.MAX_OUTPUT_BYTES + 1L);
            }
            return new ImageProcessingTool.Execution(0, false);
        });
        assertTrue(process(oversized, workspace, List.of("input.png"))
                .contains("10,000,000 byte limit"));

        Files.copy(workspace.resolve("input.png"), workspace.resolve("second.png"));
        ImageProcessingTool aggregate = tool((command, runtime, timeout) -> {
            byte[] bytes = new byte[5_000_001];
            System.arraycopy(webp("x"), 0, bytes, 0, webp("x").length);
            Files.write(outputFile(command), bytes);
            return new ImageProcessingTool.Execution(0, false);
        });
        assertTrue(process(aggregate, workspace, List.of("input.png", "second.png"))
                .contains("Combined image output"));

        assertNoRuntime();
    }

    @Test
    void reportsMissingExecutableTimeoutAndInterruptionThenCleansRuntime() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool missing = tool((command, runtime, timeout) -> {
            throw new IOException("magick unavailable");
        });
        assertTrue(process(missing, workspace, List.of("input.png"))
                .contains("magick unavailable"));

        ImageProcessingTool timeout = tool((command, runtime, limit) ->
                new ImageProcessingTool.Execution(-1, true));
        assertTrue(process(timeout, workspace, List.of("input.png"))
                .contains("30 second limit"));

        ImageProcessingTool interrupted = tool((command, runtime, timeoutLimit) -> {
            throw new InterruptedException("cancelled");
        });
        try {
            assertTrue(process(interrupted, workspace, List.of("input.png"))
                    .contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNoRuntime();
    }

    @Test
    void rejectsInputReplacedWithExternalSymlinkAfterValidation() throws Exception {
        Path workspace = workspace();
        Path second = Files.copy(workspace.resolve("input.png"),
                workspace.resolve("second.png"));
        Path outside = Files.copy(workspace.resolve("input.png"),
                temporary.resolve("outside.png"));
        try {
            Path probe = Files.createSymbolicLink(workspace.resolve("probe.png"), outside);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links: " + e.getMessage());
        }

        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, runtime, timeout) -> {
            Files.write(outputFile(command), webp("processed"));
            if (calls.incrementAndGet() == 1) {
                Files.delete(second);
                Files.createSymbolicLink(second, outside);
            }
            return new ImageProcessingTool.Execution(0, false);
        });

        String result = process(tool, workspace, List.of("input.png", "second.png"));

        assertTrue(result.contains("\"error\""), result);
        assertEquals(1, calls.get());
        assertNoRuntime();
    }

    @Test
    void rejectsASymlinkedManagedRoot() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside-root"));
        Path workspace = Files.createDirectory(outside.resolve("job"));
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png",
                workspace.resolve("input.png").toFile());
        Path linkedRoot = temporary.resolve("linked-root");
        try {
            Files.createSymbolicLink(linkedRoot, outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links: " + e.getMessage());
        }

        ImageProcessingTool tool = new ImageProcessingTool(linkedRoot, "magick",
                (command, runtime, timeout) -> {
                    throw new AssertionError("Symlinked roots must fail before execution");
                });

        assertTrue(process(tool, workspace, List.of("input.png"))
                .contains("must not traverse symbolic links"));
        assertNoRuntime();
    }

    @Test
    void springDiscoversToolWithoutLaunchingNativeProcess() {
        try (var context = new AnnotationConfigApplicationContext(ImageProcessingTool.class)) {
            assertTrue(context.getBeansOfType(ai.ultimate.tools.UltimateTool.class).values()
                    .stream().anyMatch(ImageProcessingTool.class::isInstance));
        }
    }

    @Test
    void nativeRunnerDrainsOutputWithoutDeadlockingAndEnforcesTimeout() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), "finish");
        assertEquals(new ImageProcessingTool.Execution(0, false),
                ImageProcessingTool.runCommand(command, temporary, Duration.ofSeconds(10)));

        List<String> stalled = new ArrayList<>(command);
        stalled.set(stalled.size() - 1, "stall");
        long start = System.nanoTime();
        assertTrue(ImageProcessingTool.runCommand(
                stalled, temporary, Duration.ofMillis(200)).timedOut());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(
                Duration.ofSeconds(5)) < 0);
    }

    @Test
    void nativeImageMagickSmokeReturnsArtifactAndLeavesNoGeneratedFile() throws Exception {
        boolean available = false;
        try {
            Process probe = new ProcessBuilder("magick", "-version")
                    .redirectErrorStream(true).start();
            available = probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0;
            if (probe.isAlive()) {
                probe.destroyForcibly();
            }
            probe.getInputStream().close();
        } catch (IOException ignored) {
            // Optional native smoke runs only where ImageMagick 7 is installed.
        }
        assumeTrue(available, "ImageMagick 7 is not installed");

        Path workspace = workspace();
        ImageProcessingTool tool = new ImageProcessingTool(
                temporary, "magick", ImageProcessingTool::runCommand);
        String result = tool.processImages(workspace.toString(), List.of("input.png"),
                "crop", "png", 20, 20, true, 100, 100, 90, "");

        assertFalse(result.contains("\"error\""), result);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(firstArtifact(result)));
        assertNotNull(image);
        assertEquals(20, image.getWidth());
        assertEquals(20, image.getHeight());
        try (var files = Files.list(workspace)) {
            assertEquals(List.of("input.png"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
        assertNoRuntime();
    }

    public static class ProcessFixture {
        public static void main(String[] args) throws Exception {
            for (int i = 0; i < 1024; i++) {
                System.out.print("a".repeat(4096));
                System.err.print("b".repeat(4096));
            }
            if ("stall".equals(args[0])) {
                Thread.sleep(60_000);
            }
        }
    }
}
