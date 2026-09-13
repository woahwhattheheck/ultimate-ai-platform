package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class ImageProcessingTool implements UltimateTool {
    static final long MAX_INPUT_BYTES = 20L * 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = 10_000_000;
    static final int MAX_TOTAL_OUTPUT_BYTES = 10_000_000;
    static final int MAX_BATCH = 4;
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

    @Tool(description = "Convert one to four local PNG, JPEG or WebP images from a managed workspace. "
            + "Use for compression, resizing, centered cropping, brightness/saturation correction "
            + "and text watermarking. Metadata is always stripped. Returns bounded Base64 artifacts "
            + "and never persists generated images. Every tool-created workspace file is purged "
            + "before return. Requires ImageMagick 7 (magick) on the host.")
    public String processImages(
            @ToolParam(description = "Absolute source workspace below ~/ultimate-managed-workspaces")
            String workspacePath,
            @ToolParam(description = "One to four relative input paths, for example [photos/front.png]")
            List<String> inputPaths,
            @ToolParam(description = "Artifact prefix, letters/numbers/dashes/underscores, for example thumbnail")
            String outputPrefix,
            @ToolParam(description = "png, jpeg or webp") String outputFormat,
            @ToolParam(description = "Width 1-4096; use zero with height zero to retain dimensions") int width,
            @ToolParam(description = "Height 1-4096; use zero with width zero to retain dimensions") int height,
            @ToolParam(description = "True: resize to fill then center crop; false: fit preserving aspect ratio")
            boolean crop,
            @ToolParam(description = "Brightness percentage 0-200; 100 leaves brightness unchanged")
            int brightness,
            @ToolParam(description = "Saturation percentage 0-200; 100 leaves saturation unchanged")
            int saturation,
            @ToolParam(description = "Compression quality 1-100, for example 85") int quality,
            @ToolParam(description = "Optional plain text watermark; empty string disables it")
            String watermark) {
        Path runtime = null;
        String result;
        try {
            String format = outputFormat == null ? "" : outputFormat.toLowerCase(Locale.ROOT);
            validate(inputPaths, outputPrefix, format, width, height, crop,
                    brightness, saturation, quality, watermark);

            Path root = canonicalRoot();
            Path workspace = Path.of(workspacePath).toRealPath();
            if (!workspace.startsWith(root) || workspace.equals(root) || !Files.isDirectory(workspace)) {
                throw new IllegalArgumentException(
                        "Source workspace must be an existing directory below the managed root.");
            }

            List<SecureInput> inputs = new ArrayList<>();
            Set<Object> seen = new HashSet<>();
            for (String inputPath : inputPaths) {
                SecureInput input = snapshotInput(resolveInput(workspace, inputPath));
                if (!seen.add(input.fileKey())) {
                    throw new IllegalArgumentException(
                            "Inputs must be distinct regular files of at most 20 MiB each.");
                }
                inputs.add(input);
            }

            runtime = Files.createTempDirectory(root, ".ultimate-image-").toRealPath();
            List<String> artifacts = new ArrayList<>();
            long totalOutputBytes = 0;

            for (int i = 0; i < inputs.size(); i++) {
                SecureInput original = inputs.get(i);
                byte[] contents = readInputSecure(original);

                Path stagedInput = runtime.resolve("input-" + i);
                Files.write(stagedInput, contents);

                String coder = detectFormat(stagedInput);
                Path stagedOutput = Files.createFile(
                        runtime.resolve("output-" + i + "." + format)).toRealPath();
                List<String> command = command(executable, coder, stagedInput.toRealPath(),
                        stagedOutput, format, width, height, crop, brightness,
                        saturation, quality, watermark);
                Execution execution = runner.run(command, runtime, TIMEOUT);
                if (execution.timedOut()) {
                    throw new IOException("ImageMagick exceeded the 30 second limit.");
                }
                if (execution.exitCode() != 0) {
                    throw new IOException("ImageMagick exited with code " + execution.exitCode() + ".");
                }

                byte[] output;
                if (!Files.isRegularFile(stagedOutput, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ImageMagick output is not a regular file.");
                }
                try (InputStream stream = Files.newInputStream(stagedOutput, LinkOption.NOFOLLOW_LINKS)) {
                    output = stream.readNBytes(MAX_OUTPUT_BYTES + 1);
                }
                if (output.length == 0 || output.length > MAX_OUTPUT_BYTES) {
                    throw new IOException(
                            "ImageMagick output is empty or exceeds the 10,000,000 byte limit.");
                }
                if (!hasFormat(output, format)) {
                    throw new IOException("ImageMagick output does not match the requested format.");
                }
                totalOutputBytes += output.length;
                if (totalOutputBytes > MAX_TOTAL_OUTPUT_BYTES) {
                    throw new IOException(
                            "Combined image output exceeds the 10,000,000 byte response limit.");
                }

                String name = outputPrefix + "-" + (i + 1) + "." + format;
                artifacts.add("{\"name\":\"" + name + "\",\"encoding\":\"base64\","
                        + "\"bytes\":" + output.length + ",\"data\":\""
                        + Base64.getEncoder().encodeToString(output) + "\"}");
            }
            result = "{\"images\":[" + String.join(",", artifacts) + "]}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = error("Processing was interrupted.");
        } catch (Exception e) {
            result = error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (runtime != null) {
                try {
                    cleanup(runtime);
                } catch (IOException | RuntimeException e) {
                    result = error(
                            "Temporary image workspace could not be completely removed; operator intervention is required.");
                }
            }
        }
        return result;
    }

    private Path canonicalRoot() throws IOException {
        for (Path current = managedRoot; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Managed workspace root must not traverse symbolic links.");
            }
        }
        Path root = managedRoot.toRealPath();
        if (!root.equals(managedRoot)) {
            throw new IOException("Managed workspace root must resolve to its configured path.");
        }
        return root;
    }

    private static void validate(List<String> inputs, String prefix, String format,
                                 int width, int height, boolean crop, int brightness,
                                 int saturation, int quality, String watermark) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Supply one to four input paths.");
        }
        if (prefix == null || !prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException(
                    "Output prefix must contain 1-64 letters, numbers, dashes or underscores.");
        }
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("Output format must be png, jpeg or webp.");
        }
        if (!((width == 0 && height == 0 && !crop)
                || (width >= 1 && width <= 4096 && height >= 1 && height <= 4096))) {
            throw new IllegalArgumentException(
                    "Dimensions must both be 1-4096, or both zero without cropping.");
        }
        if (brightness < 0 || brightness > 200 || saturation < 0 || saturation > 200
                || quality < 1 || quality > 100) {
            throw new IllegalArgumentException(
                    "Brightness/saturation must be 0-200 and quality 1-100.");
        }
        if (watermark == null || watermark.length() > 120
                || watermark.codePoints().anyMatch(c ->
                        Character.isISOControl(c) || c == '%' || c == '\\' || c == '@')) {
            throw new IllegalArgumentException(
                    "Watermark must be at most 120 plain characters, without controls, %, backslash or @.");
        }
    }

    private static Path resolveInput(Path workspace, String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Input paths must be nonempty and relative.");
        }
        Path path = Path.of(relative);
        Path candidate = workspace.resolve(path).normalize();
        if (path.isAbsolute() || !candidate.startsWith(workspace)) {
            throw new IllegalArgumentException("Input path escapes the source workspace.");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(workspace)) {
            throw new IllegalArgumentException("Input path resolves outside the source workspace.");
        }
        return real;
    }

    static SecureInput snapshotInput(Path input) throws IOException {
        SecureInput metadata = snapshotInputMetadata(input);
        byte[] contents = readInputSecure(metadata);
        return new SecureInput(metadata.path(), metadata.directoryKeys(), metadata.fileKey(),
                metadata.size(), metadata.lastModifiedTime(), sha256(contents));
    }

    private static SecureInput snapshotInputMetadata(Path input) throws IOException {
        Path absolute = input.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path filesystemRoot = absolute.getRoot();
        if (parent == null || filesystemRoot == null || absolute.getFileName() == null) {
            throw new IOException("Input path has no secure parent directory.");
        }

        List<Object> directoryKeys = new ArrayList<>();
        BasicFileAttributes rootAttributes = Files.readAttributes(
                filesystemRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        directoryKeys.add(requireDirectoryKey(rootAttributes));
        Path current = filesystemRoot;
        for (Path component : parent) {
            current = current.resolve(component);
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            directoryKeys.add(requireDirectoryKey(attributes));
        }

        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Inputs must be distinct regular files of at most 20 MiB each.");
        }
        Object fileKey = requireFileKey(attributes, "input file");
        return new SecureInput(absolute, List.copyOf(directoryKeys), fileKey,
                attributes.size(), attributes.lastModifiedTime(), null);
    }

    static byte[] readInputSecure(SecureInput input) throws IOException {
        Path absolute = input.path();
        Path parent = absolute.getParent();
        Path filesystemRoot = absolute.getRoot();
        if (parent == null || filesystemRoot == null || absolute.getFileName() == null) {
            throw new IOException("Input path has no secure parent directory.");
        }

        DirectoryStream<Path> stream = Files.newDirectoryStream(filesystemRoot);
        if (!(stream instanceof SecureDirectoryStream<?>)) {
            stream.close();
            throw new IOException(
                    "Secure directory traversal is unavailable on this filesystem.");
        }

        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) stream;
        try {
            if (input.directoryKeys().isEmpty()) {
                throw new IOException("Secure input directory identity is incomplete.");
            }
            requireDirectoryIdentity(readAttributes(current, Path.of(".")),
                    input.directoryKeys().getFirst());
            int identityIndex = 1;
            for (Path component : parent) {
                if (identityIndex >= input.directoryKeys().size()) {
                    throw new IOException("Secure input directory identity is incomplete.");
                }
                Object expectedKey = input.directoryKeys().get(identityIndex++);
                requireDirectoryIdentity(readAttributes(current, component), expectedKey);

                SecureDirectoryStream<Path> next =
                        current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                boolean accepted = false;
                try {
                    requireDirectoryIdentity(readAttributes(next, Path.of(".")), expectedKey);
                    accepted = true;
                } finally {
                    if (!accepted) {
                        next.close();
                    }
                }
                SecureDirectoryStream<Path> previous = current;
                current = next;
                previous.close();
            }
            if (identityIndex != input.directoryKeys().size()) {
                throw new IOException("Secure input directory identity is incomplete.");
            }

            Path fileName = absolute.getFileName();
            requireFileIdentity(readAttributes(current, fileName), input);
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = current.newByteChannel(fileName, options);
                 InputStream inputStream = Channels.newInputStream(channel)) {
                if (channel.size() != input.size()) {
                    throw new IOException("Input changed after validation.");
                }
                byte[] contents = inputStream.readNBytes((int) MAX_INPUT_BYTES + 1);
                if (contents.length > MAX_INPUT_BYTES) {
                    throw new IllegalArgumentException("Input grew beyond the 20 MiB limit.");
                }
                if (channel.size() != input.size()) {
                    throw new IOException("Input changed while it was being staged.");
                }
                requireFileIdentity(readAttributes(current, fileName), input);
                if (input.contentSha256() != null
                        && !input.contentSha256().equals(sha256(contents))) {
                    throw new IOException("Input content changed after validation.");
                }
                return contents;
            }
        } finally {
            current.close();
        }
    }

    private static BasicFileAttributes readAttributes(
            SecureDirectoryStream<Path> directory, Path path) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(
                path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new IOException("Secure input attributes are unavailable.");
        }
        return view.readAttributes();
    }

    private static Object requireDirectoryKey(BasicFileAttributes attributes) throws IOException {
        if (!attributes.isDirectory()) {
            throw new IOException("Input ancestor is not a directory.");
        }
        return requireFileKey(attributes, "input directory");
    }

    private static Object requireFileKey(BasicFileAttributes attributes, String kind)
            throws IOException {
        Object fileKey = attributes.fileKey();
        if (fileKey == null) {
            throw new IOException("Secure identity is unavailable for " + kind + ".");
        }
        return fileKey;
    }

    private static void requireDirectoryIdentity(
            BasicFileAttributes attributes, Object expectedKey) throws IOException {
        if (!attributes.isDirectory()
                || !Objects.equals(requireFileKey(attributes, "input directory"), expectedKey)) {
            throw new IOException("Input ancestor changed after validation.");
        }
    }

    private static void requireFileIdentity(
            BasicFileAttributes attributes, SecureInput input) throws IOException {
        if (!attributes.isRegularFile()
                || !Objects.equals(requireFileKey(attributes, "input file"), input.fileKey())
                || attributes.size() != input.size()
                || !attributes.lastModifiedTime().equals(input.lastModifiedTime())) {
            throw new IOException("Input changed after validation.");
        }
    }

    private static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private static String detectFormat(Path input) throws IOException {
        byte[] header;
        try (InputStream stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
            header = stream.readNBytes(12);
        }
        if (hasFormat(header, "png")) {
            return "PNG";
        }
        if (hasFormat(header, "jpeg")) {
            return "JPEG";
        }
        if (hasFormat(header, "webp")) {
            return "WEBP";
        }
        throw new IllegalArgumentException("Only PNG, JPEG and WebP image contents are accepted.");
    }

    private static boolean hasFormat(byte[] bytes, String format) {
        return switch (format) {
            case "png" -> bytes.length >= 8
                    && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                    && bytes[2] == 'N' && bytes[3] == 'G'
                    && bytes[4] == 13 && bytes[5] == 10
                    && bytes[6] == 26 && bytes[7] == 10;
            case "jpeg" -> bytes.length >= 3
                    && bytes[0] == (byte) 0xff
                    && bytes[1] == (byte) 0xd8
                    && bytes[2] == (byte) 0xff;
            case "webp" -> bytes.length >= 12
                    && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
    }

    private static List<String> command(String executable, String coder, Path input,
                                        Path output, String format, int width, int height,
                                        boolean crop, int brightness, int saturation,
                                        int quality, String watermark) {
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
        args.addAll(List.of("-colorspace", "sRGB",
                "-modulate", brightness + "," + saturation + ",100"));
        if (!watermark.isEmpty()) {
            args.addAll(List.of("-font", "DejaVu-Sans", "-pointsize", "18",
                    "-gravity", "southeast", "-fill", "white", "-stroke", "#00000080",
                    "-strokewidth", "1", "-annotate", "+10+10", watermark));
        }
        args.addAll(List.of("-strip", "-quality", Integer.toString(quality),
                format.toUpperCase(Locale.ROOT) + ":" + output));
        return List.copyOf(args);
    }

    static Execution runCommand(List<String> command, Path runtime, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(runtime.toFile()).redirectErrorStream(true);
        builder.environment().put("MAGICK_TEMPORARY_PATH", runtime.toString());
        builder.environment().put("HOME", runtime.toString());
        builder.environment().put("TMPDIR", runtime.toString());
        builder.environment().put("TMP", runtime.toString());
        builder.environment().put("TEMP", runtime.toString());
        Process process = builder.start();
        Thread drain = Thread.startVirtualThread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (stream.read(buffer) >= 0) {
                    // Drain continuously without retaining arbitrary native output.
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
                try {
                    process.getInputStream().close();
                } finally {
                    drain.interrupt();
                }
            }
        }
    }

    private static void cleanup(Path runtime) throws IOException {
        Files.walkFileTree(runtime, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String error(String message) {
        String safe = message == null ? "Image processing failed." : message;
        safe = safe.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ").replace("\t", " ");
        return "{\"error\":\"Image Processing Error: " + safe + "\"}";
    }

    @FunctionalInterface
    interface CommandRunner {
        Execution run(List<String> command, Path runtime, Duration timeout)
                throws IOException, InterruptedException;
    }

    record SecureInput(Path path, List<Object> directoryKeys, Object fileKey,
                       long size, FileTime lastModifiedTime, String contentSha256) { }

    record Execution(int exitCode, boolean timedOut) { }
}
