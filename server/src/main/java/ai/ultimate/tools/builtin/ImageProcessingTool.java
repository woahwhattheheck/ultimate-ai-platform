package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class ImageProcessingTool implements UltimateTool {
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "webp");
    private final Path managedRoot;
    private final String executable;
    private final CommandRunner runner;

    public ImageProcessingTool() {
        this(Path.of(System.getProperty("user.home"), "ultimate-managed-workspaces"),
                "magick", ImageProcessingTool::runCommand);
    }

    ImageProcessingTool(Path managedRoot, String executable, CommandRunner runner) {
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.executable = executable;
        this.runner = runner;
    }

    @Tool(description = "Convert a batch of local PNG, JPEG or WebP images in a managed workspace. "
            + "Use for compression, resizing, centered cropping, brightness/saturation correction "
            + "and text watermarking. Metadata is always stripped. Returns relative output paths "
            + "or a validation/runtime error. Existing files are never overwritten. "
            + "Requires ImageMagick 7 (magick) on the host.")
    public String processImages(
            @ToolParam(description = "Absolute workspace below ~/ultimate-managed-workspaces") String workspacePath,
            @ToolParam(description = "One to ten relative input paths, for example [photos/front.png]") List<String> inputPaths,
            @ToolParam(description = "Existing relative output directory, for example results") String outputDirectory,
            @ToolParam(description = "Output prefix, letters/numbers/dashes/underscores, for example thumbnail") String outputPrefix,
            @ToolParam(description = "png, jpeg or webp") String outputFormat,
            @ToolParam(description = "Width 1-4096; use zero with height zero to retain dimensions") int width,
            @ToolParam(description = "Height 1-4096; use zero with width zero to retain dimensions") int height,
            @ToolParam(description = "True: resize to fill then center crop; false: fit preserving aspect ratio") boolean crop,
            @ToolParam(description = "Brightness percentage 0-200; 100 leaves brightness unchanged") int brightness,
            @ToolParam(description = "Saturation percentage 0-200; 100 leaves saturation unchanged") int saturation,
            @ToolParam(description = "Compression quality 1-100, for example 85") int quality,
            @ToolParam(description = "Optional plain text watermark; empty string disables it") String watermark) {
        Path scratch = null;
        List<Path> published = new ArrayList<>();
        String result;
        try {
            String format = outputFormat == null ? "" : outputFormat.toLowerCase(Locale.ROOT);
            validate(inputPaths, outputPrefix, format, width, height, crop, brightness, saturation, quality, watermark);
            Path root = managedRoot.toRealPath();
            Path workspace = Path.of(workspacePath).toRealPath();
            if (!workspace.startsWith(root) || workspace.equals(root) || !Files.isDirectory(workspace)) {
                throw new IllegalArgumentException("Workspace must be an existing directory below the managed root.");
            }
            Path output = resolve(workspace, outputDirectory);
            if (!Files.isDirectory(output)) {
                throw new IllegalArgumentException("Output directory must already exist.");
            }
            List<Path> inputs = new ArrayList<>();
            List<Path> destinations = new ArrayList<>();
            Set<Path> seen = new HashSet<>();
            for (int i = 0; i < inputPaths.size(); i++) {
                Path input = resolve(workspace, inputPaths.get(i));
                if (!Files.isRegularFile(input) || Files.size(input) > MAX_BYTES || !seen.add(input)) {
                    throw new IllegalArgumentException("Inputs must be distinct regular files of at most 20 MiB each.");
                }
                inputs.add(input);
                Path destination = output.resolve(outputPrefix + "-" + (i + 1) + "." + format);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("Output already exists: " + destination.getFileName());
                }
                destinations.add(destination);
            }

            scratch = Files.createTempDirectory(root, ".ultimate-image-").toRealPath();
            List<Path> stagedOutputs = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                // Generated names keep ImageMagick path syntax out of user-controlled filenames.
                Path stagedInput = scratch.resolve("input-" + i);
                Files.copy(inputs.get(i), stagedInput);
                if (Files.size(stagedInput) > MAX_BYTES) {
                    throw new IllegalArgumentException("Input grew beyond the 20 MiB limit.");
                }
                String coder = detectFormat(stagedInput);
                Path stagedOutput = Files.createFile(scratch.resolve("output-" + i + "." + format)).toRealPath();
                List<String> command = command(executable, coder, stagedInput.toRealPath(), stagedOutput,
                        format, width, height, crop, brightness, saturation, quality, watermark);
                Execution execution = runner.run(command, scratch, TIMEOUT);
                if (execution.timedOut()) {
                    throw new IOException("ImageMagick exceeded the 30 second limit.");
                }
                if (execution.exitCode() != 0) {
                    throw new IOException("ImageMagick exited with code " + execution.exitCode() + ".");
                }
                if (!Files.isRegularFile(stagedOutput, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(stagedOutput) == 0 || Files.size(stagedOutput) > MAX_BYTES) {
                    throw new IOException("ImageMagick output is empty, invalid or larger than 20 MiB.");
                }
                stagedOutputs.add(stagedOutput);
            }
            // Publish only once every input has succeeded. Files.move has no replacement option.
            for (int i = 0; i < destinations.size(); i++) {
                if (!output.equals(resolve(workspace, outputDirectory))) {
                    throw new IOException("Output directory changed during processing.");
                }
                Files.move(stagedOutputs.get(i), destinations.get(i));
                published.add(destinations.get(i));
            }
            result = "Image processing completed: " + published.stream()
                    .map(workspace::relativize).map(Path::toString).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "Image Processing Error: Processing was interrupted.";
            rollback(published);
        } catch (Exception e) {
            result = "Image Processing Error: " + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
            rollback(published);
        } finally {
            // Scratch contains staged copies, intermediate outputs and ImageMagick pixel caches.
            if (scratch != null) {
                try (var paths = Files.walk(scratch)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    // Report cleanup failure instead of claiming a completely successful operation.
                    result = "Image Processing Error: Temporary image files could not be completely removed.";
                }
            }
        }
        return result;
    }

    private static void validate(List<String> inputs, String prefix, String format,
                                 int width, int height, boolean crop, int brightness, int saturation,
                                 int quality, String watermark) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 10) {
            throw new IllegalArgumentException("Supply one to ten input paths.");
        }
        if (prefix == null || !prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Output prefix must contain 1-64 letters, numbers, dashes or underscores.");
        }
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("Output format must be png, jpeg or webp.");
        }
        if (!((width == 0 && height == 0 && !crop)
                || (width >= 1 && width <= 4096 && height >= 1 && height <= 4096))) {
            throw new IllegalArgumentException("Dimensions must both be 1-4096, or both zero without cropping.");
        }
        if (brightness < 0 || brightness > 200 || saturation < 0 || saturation > 200
                || quality < 1 || quality > 100) {
            throw new IllegalArgumentException("Brightness/saturation must be 0-200 and quality 1-100.");
        }
        // ImageMagick expands percent properties and backslash escapes even without a shell.
        if (watermark == null || watermark.length() > 120
                || watermark.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '%' || c == '\\' || c == '@')) {
            throw new IllegalArgumentException("Watermark must be at most 120 plain characters, without controls, %, backslash or @.");
        }
    }

    private static Path resolve(Path workspace, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Paths must be nonempty and relative.");
        }
        Path path = Path.of(relative);
        Path candidate = workspace.resolve(path).normalize();
        if (path.isAbsolute() || !candidate.startsWith(workspace)) {
            throw new IllegalArgumentException("Path escapes the workspace.");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(workspace)) {
            throw new IllegalArgumentException("Path resolves outside the workspace.");
        }
        return real;
    }

    private static String detectFormat(Path input) throws IOException {
        byte[] header;
        try (InputStream stream = Files.newInputStream(input)) {
            header = stream.readNBytes(12);
        }
        if (header.length >= 8 && header[0] == (byte) 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G' && header[4] == 13
                && header[5] == 10 && header[6] == 26 && header[7] == 10) {
            return "PNG";
        }
        if (header.length >= 3 && header[0] == (byte) 0xff
                && header[1] == (byte) 0xd8 && header[2] == (byte) 0xff) {
            return "JPEG";
        }
        if (header.length >= 12 && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "WEBP";
        }
        throw new IllegalArgumentException("Only PNG, JPEG and WebP image contents are accepted.");
    }

    private static List<String> command(String executable, String coder, Path input, Path output,
                                        String format, int width, int height, boolean crop,
                                        int brightness, int saturation, int quality, String watermark) {
        List<String> args = new ArrayList<>(List.of(executable,
                "-limit", "thread", "2", "-limit", "memory", "128MiB",
                "-limit", "map", "256MiB", "-limit", "disk", "256MiB",
                "-limit", "width", "8192", "-limit", "height", "8192",
                "-limit", "time", "30", coder + ":" + input + "[0]", "-auto-orient"));
        if (width > 0) {
            args.addAll(List.of("-resize", width + "x" + height + (crop ? "^" : ">")));
            if (crop) {
                args.addAll(List.of("-gravity", "center", "-extent", width + "x" + height));
            }
        }
        args.addAll(List.of("-colorspace", "sRGB", "-modulate", brightness + "," + saturation + ",100"));
        if (!watermark.isEmpty()) {
            args.addAll(List.of("-font", "DejaVu-Sans", "-pointsize", "18",
                    "-gravity", "southeast", "-fill", "white", "-stroke", "#00000080",
                    "-strokewidth", "1", "-annotate", "+10+10", watermark));
        }
        args.addAll(List.of("-strip", "-quality", Integer.toString(quality),
                format.toUpperCase(Locale.ROOT) + ":" + output));
        return List.copyOf(args);
    }

    private static void rollback(List<Path> published) {
        for (Path path : published) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // The caller already receives a failure; preserve the original exception.
            }
        }
    }

    static Execution runCommand(List<String> command, Path scratch, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(scratch.toFile()).redirectErrorStream(true);
        builder.environment().put("MAGICK_TEMPORARY_PATH", scratch.toString());
        Process process = builder.start();
        Thread drain = Thread.startVirtualThread(() -> {
            // Drain continuously without retaining arbitrary native-process output in memory.
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (stream.read(buffer) >= 0) {
                    // Diagnostics are deliberately not included in model-visible responses.
                }
            } catch (IOException ignored) {
                // Closing the pipe during timeout/interruption is expected.
            }
        });
        try {
            process.getOutputStream().close();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Execution(completed ? process.exitValue() : -1, !completed);
        } finally {
            try {
                if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                // waitFor clears interruption; keep cleanup bounded when cancellation arrives.
                boolean interrupted = Thread.interrupted();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            } finally {
                process.getInputStream().close();
                drain.interrupt();
            }
        }
    }

    @FunctionalInterface
    interface CommandRunner {
        Execution run(List<String> command, Path scratch, Duration timeout) throws IOException, InterruptedException;
    }

    record Execution(int exitCode, boolean timedOut) { }
}
