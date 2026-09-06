package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolTest {
    @TempDir
    Path temporary;

    private Path workspace() throws IOException {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.createDirectory(workspace.resolve("results"));
        ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB), "png",
                workspace.resolve("input.png").toFile());
        return workspace;
    }

    private String process(ImageProcessingTool tool, Path workspace, List<String> inputs, String output) {
        return tool.processImages(workspace.toString(), inputs, output, "result", "webp",
                20, 20, true, 110, 90, 85, "Example watermark");
    }

    private ImageProcessingTool tool(ImageProcessingTool.CommandRunner runner) {
        return new ImageProcessingTool(temporary, "magick", runner);
    }

    private static Path outputFile(List<String> command) {
        String argument = command.getLast();
        return Path.of(argument.substring(argument.indexOf(':') + 1));
    }

    private void assertClean(Path workspace) throws IOException {
        try (var paths = Files.list(temporary)) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".ultimate-image-"));
        }
    }

    @Test
    void buildsAllowlistedBatchCommandsAndPublishesOnlyResults() throws Exception {
        Path workspace = workspace();
        Files.copy(workspace.resolve("input.png"), workspace.resolve("a % [0]; echo.png"));
        List<List<String>> commands = new ArrayList<>();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            commands.add(command);
            assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
            assertThat(scratch).startsWith(temporary);
            Files.writeString(outputFile(command), "processed");
            return new ImageProcessingTool.Execution(0, false);
        });
        String result = process(tool, workspace, List.of("input.png", "a % [0]; echo.png"), "results");

        assertThat(result).contains("Image processing completed", "result-1.webp", "result-2.webp");
        assertThat(commands).hasSize(2);
        assertThat(commands.getFirst()).containsSubsequence("-resize", "20x20^", "-gravity", "center", "-extent", "20x20");
        assertThat(commands.getFirst()).containsSubsequence("-modulate", "110,90,100");
        assertThat(commands.getFirst()).containsSubsequence("-annotate", "+10+10", "Example watermark");
        assertThat(commands.getFirst()).containsSubsequence("-strip", "-quality", "85");
        assertThat(commands.get(1)).noneMatch(s -> s.contains("echo.png"));
        assertThat(commands.getFirst()).anyMatch(s -> s.startsWith("PNG:") && s.endsWith("[0]"));
        assertThat(Files.readString(workspace.resolve("results/result-1.webp"))).isEqualTo("processed");
        assertThat(workspace.resolve("input.png")).exists();
        assertClean(workspace);
    }

    @Test
    void rejectsTraversalAndAbsolutePathsBeforeExecution() throws Exception {
        Path workspace = workspace();
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            calls.incrementAndGet();
            return new ImageProcessingTool.Execution(0, false);
        });
        Files.copy(workspace.resolve("input.png"), temporary.resolve("outside.png"));

        assertThat(process(tool, workspace, List.of("../outside.png"), "results")).contains("escapes");
        assertThat(process(tool, workspace, List.of(temporary.resolve("outside.png").toString()), "results")).contains("escapes");
        assertThat(process(tool, workspace, List.of("input.png"), "..")).contains("escapes");
        assertThat(process(tool, temporary, List.of("outside.png"), "workspace")).contains("below the managed root");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsSymlinksThatResolveOutsideWorkspace() throws Exception {
        Path workspace = workspace();
        Path external = Files.copy(workspace.resolve("input.png"), temporary.resolve("outside.png"));
        try {
            Files.createSymbolicLink(workspace.resolve("link.png"), external);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links: " + e.getMessage());
        }
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            throw new AssertionError("Must reject the path before starting ImageMagick");
        });
        assertThat(process(tool, workspace, List.of("link.png"), "results")).contains("outside the workspace");
    }

    @Test
    void rejectsDisguisedImageScriptAndCleansStaging() throws Exception {
        Path workspace = workspace();
        Files.writeString(workspace.resolve("script.png"), "<image><read filename=\"/etc/passwd\"/></image>");
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            throw new AssertionError("Must reject non-raster contents");
        });
        assertThat(process(tool, workspace, List.of("script.png"), "results")).contains("Only PNG, JPEG and WebP");
        assertClean(workspace);
    }

    @Test
    void keepsExistingOutputIncludingDanglingSymlink() throws Exception {
        Path workspace = workspace();
        Path existing = Files.writeString(workspace.resolve("results/result-1.webp"), "original");
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            throw new AssertionError("Must not start ImageMagick for an existing destination");
        });
        assertThat(process(tool, workspace, List.of("input.png"), "results")).contains("already exists");
        assertThat(Files.readString(existing)).isEqualTo("original");
        Files.delete(existing);
        try {
            Files.createSymbolicLink(existing, workspace.resolve("missing"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links");
        }
        assertThat(process(tool, workspace, List.of("input.png"), "results")).contains("already exists");
        assertThat(Files.isSymbolicLink(existing)).isTrue();
    }

    @Test
    void rejectsNullsInvalidDimensionsAndNativeWatermarkExpressions() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            throw new AssertionError("Invalid request should not start ImageMagick");
        });
        for (String watermark : new String[]{"%[fx:1+1]", "@secret", "line\nline", "\\n", null}) {
            assertThat(tool.processImages(workspace.toString(), List.of("input.png"), "results", "result",
                    "webp", 10, 10, false, 100, 100, 80, watermark)).contains("Image Processing Error");
        }
        assertThat(process(tool, workspace, null, "results")).contains("one to ten");
        assertThat(tool.processImages(workspace.toString(), List.of("input.png"), "results", "result",
                "png", 0, 10, false, 100, 100, 80, "")).contains("Dimensions");
        assertThat(tool.processImages(workspace.toString(), List.of("input.png"), "results", "result",
                "pdf", 0, 0, false, 100, 100, 80, "")).contains("format");
    }

    @Test
    void rejectsDuplicateAndOversizedInputs() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            throw new AssertionError("Invalid request should not start ImageMagick");
        });
        assertThat(process(tool, workspace, List.of("input.png", "./input.png"), "results")).contains("distinct");
        try (var file = new java.io.RandomAccessFile(workspace.resolve("large.png").toFile(), "rw")) {
            file.setLength(20L * 1024 * 1024 + 1);
        }
        assertThat(process(tool, workspace, List.of("large.png"), "results")).contains("20 MiB");
    }

    @Test
    void doesNotPublishPartialBatchOnFailure() throws Exception {
        Path workspace = workspace();
        Files.copy(workspace.resolve("input.png"), workspace.resolve("second.png"));
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            Files.writeString(outputFile(command), "processed");
            return new ImageProcessingTool.Execution(calls.incrementAndGet() == 2 ? 1 : 0, false);
        });
        assertThat(process(tool, workspace, List.of("input.png", "second.png"), "results")).contains("exited with code 1");
        try (var paths = Files.list(workspace.resolve("results"))) {
            assertThat(paths).isEmpty();
        }
        assertClean(workspace);
    }

    @Test
    void rollsBackPublishedBatchWhenAnotherWriterCreatesDestination() throws Exception {
        Path workspace = workspace();
        Files.copy(workspace.resolve("input.png"), workspace.resolve("second.png"));
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            Files.writeString(outputFile(command), "processed");
            if (calls.incrementAndGet() == 2) {
                Files.writeString(workspace.resolve("results/result-2.webp"), "other writer");
            }
            return new ImageProcessingTool.Execution(0, false);
        });
        assertThat(process(tool, workspace, List.of("input.png", "second.png"), "results")).contains("Image Processing Error");
        assertThat(workspace.resolve("results/result-1.webp")).doesNotExist();
        assertThat(Files.readString(workspace.resolve("results/result-2.webp"))).isEqualTo("other writer");
        assertClean(workspace);
    }

    @Test
    void reportsMissingExecutableAndTimeoutAndCleansScratch() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool missing = tool((command, scratch, timeout) -> {
            throw new IOException("magick unavailable");
        });
        assertThat(process(missing, workspace, List.of("input.png"), "results")).contains("magick unavailable");
        ImageProcessingTool timeout = tool((command, scratch, limit) -> new ImageProcessingTool.Execution(-1, true));
        assertThat(process(timeout, workspace, List.of("input.png"), "results")).contains("30 second limit");
        assertClean(workspace);
    }

    @Test
    void interruptionPreservesFlagAndCleansScratch() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            throw new InterruptedException("cancelled");
        });
        try {
            assertThat(process(tool, workspace, List.of("input.png"), "results")).contains("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertClean(workspace);
    }

    @Test
    void rejectsInputReplacedWithExternalSymlinkAfterValidation() throws Exception {
        Path workspace = workspace();
        Path second = Files.copy(workspace.resolve("input.png"), workspace.resolve("second.png"));
        Path external = Files.copy(workspace.resolve("input.png"), temporary.resolve("outside.png"));
        try {
            Path probe = Files.createSymbolicLink(workspace.resolve("probe.png"), external);
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links");
        }
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            calls.incrementAndGet();
            Files.writeString(outputFile(command), "processed");
            Files.delete(second);
            Files.createSymbolicLink(second, external);
            return new ImageProcessingTool.Execution(0, false);
        });
        assertThat(process(tool, workspace, List.of("input.png", "second.png"), "results"))
                .contains("Image Processing Error");
        assertThat(calls).hasValue(1);
        assertThat(workspace.resolve("results/result-1.webp")).doesNotExist();
        assertClean(workspace);
    }

    @Test
    void successfulExitWithoutOutputIsAnError() throws Exception {
        Path workspace = workspace();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> new ImageProcessingTool.Execution(0, false));
        assertThat(process(tool, workspace, List.of("input.png"), "results")).contains("output is empty");
        assertThat(workspace.resolve("results/result-1.webp")).doesNotExist();
        assertClean(workspace);
    }

    @Test
    void simultaneousSameDestinationHasOneWinner() throws Exception {
        Path workspace = workspace();
        var ready = new java.util.concurrent.CyclicBarrier(2);
        AtomicInteger identities = new AtomicInteger();
        ImageProcessingTool tool = tool((command, scratch, timeout) -> {
            Files.writeString(outputFile(command), "writer-" + identities.incrementAndGet());
            try {
                ready.await(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                throw new IOException(e);
            }
            return new ImageProcessingTool.Execution(0, false);
        });
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> process(tool, workspace, List.of("input.png"), "results"));
            var second = pool.submit(() -> process(tool, workspace, List.of("input.png"), "results"));
            List<String> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(s -> s.startsWith("Image processing completed")).count()).isEqualTo(1L);
            assertThat(results.stream().filter(s -> s.startsWith("Image Processing Error")).count()).isEqualTo(1L);
        }
        assertThat(Files.readString(workspace.resolve("results/result-1.webp"))).startsWith("writer-");
        assertClean(workspace);
    }

    @Test
    void springDiscoversToolWithoutLaunchingNativeProcess() {
        try (var context = new AnnotationConfigApplicationContext(ImageProcessingTool.class)) {
            assertThat(context.getBeansOfType(ai.ultimate.tools.UltimateTool.class).values())
                    .anyMatch(ImageProcessingTool.class::isInstance);
        }
    }

    @Test
    void nativeRunnerDrainsOutputWithoutDeadlockingAndEnforcesTimeout() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                ProcessFixture.class.getName(), "finish");
        assertThat(ImageProcessingTool.runCommand(command, temporary, Duration.ofSeconds(10)))
                .isEqualTo(new ImageProcessingTool.Execution(0, false));
        List<String> stalled = new ArrayList<>(command);
        stalled.set(stalled.size() - 1, "stall");
        long start = System.nanoTime();
        assertThat(ImageProcessingTool.runCommand(stalled, temporary, Duration.ofMillis(200)).timedOut()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void imageMagickSmokeTestVerifiesOutputDimensionsAndFormat() throws Exception {
        boolean available = false;
        try {
            Process probe = new ProcessBuilder("magick", "-version").redirectErrorStream(true).start();
            available = probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0;
            if (probe.isAlive()) probe.destroyForcibly();
            probe.getInputStream().close();
        } catch (IOException ignored) {
            // This optional smoke test runs only where ImageMagick 7 is already installed.
        }
        assumeTrue(available, "ImageMagick 7 is not installed");
        Path workspace = workspace();
        ImageProcessingTool tool = new ImageProcessingTool(temporary, "magick", ImageProcessingTool::runCommand);
        String result = tool.processImages(workspace.toString(), List.of("input.png"), "results", "crop",
                "png", 20, 20, true, 100, 100, 90, "");
        assertThat(result).contains("Image processing completed");
        BufferedImage output = ImageIO.read(workspace.resolve("results/crop-1.png").toFile());
        assertThat(output.getWidth()).isEqualTo(20);
        assertThat(output.getHeight()).isEqualTo(20);
        assertClean(workspace);
    }

    public static class ProcessFixture {
        public static void main(String[] args) throws Exception {
            for (int i = 0; i < 1024; i++) {
                System.out.print("a".repeat(4096));
                System.err.print("b".repeat(4096));
            }
            if ("stall".equals(args[0])) Thread.sleep(60_000);
        }
    }
}
