package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolSecurityTest {
    @TempDir
    Path temporary;

    @Test
    void regularDirectoryReplacementCannotRedirectAnAlreadyValidatedBatchInput() throws Exception {
        assertReplacementRejected(true);
    }

    @Test
    void regularFileReplacementCannotChangeAnAlreadyValidatedBatchInput() throws Exception {
        assertReplacementRejected(false);
    }

    private void assertReplacementRejected(boolean replaceDirectory) throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path parent = Files.createDirectory(workspace.resolve("subdir"));
        Path second = parent.resolve("second.png");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>,
                    "Filesystem does not expose SecureDirectoryStream");
        }
        writeImage(workspace.resolve("first.png"), 0x102030);
        writeImage(second, 0x405060);
        AtomicInteger calls = new AtomicInteger();
        ImageProcessingTool tool = new ImageProcessingTool(temporary, "controlled-converter",
                (command, runtime, timeout) -> {
                    int call = calls.incrementAndGet();
                    String output = command.get(command.size() - 1);
                    Path outputPath = Path.of(output.substring(output.indexOf(':') + 1));
                    Files.copy(runtime.resolve("input-" + (call - 1)), outputPath,
                            StandardCopyOption.REPLACE_EXISTING);
                    if (call == 1) {
                        if (replaceDirectory) {
                            Files.move(parent, workspace.resolve("subdir-original"));
                            Files.createDirectory(parent);
                        } else {
                            Files.move(second, parent.resolve("second-original.png"));
                        }
                        writeImage(second, 0xaabbcc);
                    }
                    return new ImageProcessingTool.Execution(0, false);
                });

        String result = tool.processImages(workspace.toString(),
                List.of("first.png", "subdir/second.png"), "result", "png",
                0, 0, false, 100, 100, 100, "");

        assertTrue(result.startsWith("{\"error\":"), result);
        assertTrue(result.contains("Input changed after validation."), result);
        assertEquals(1, calls.get(), "The replacement must never reach the converter");
        try (var paths = Files.list(temporary)) {
            assertEquals(0, paths.filter(path -> path.getFileName().toString()
                    .startsWith(".ultimate-image-")).count());
        }
    }

    private static void writeImage(Path path, int color) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    @Test
    void secureInputReadRejectsAncestorSymlinkReplacement() throws Exception {
        Path inputParent = Files.createDirectories(
                temporary.resolve("managed/workspace/subdir"));
        Path input = inputParent.resolve("input.png");
        byte[] inside = "INSIDE".getBytes(StandardCharsets.UTF_8);
        Files.write(input, inside);
        Path canonicalInput = input.toRealPath();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputParent)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>,
                    "Filesystem does not expose SecureDirectoryStream");
        }

        assertArrayEquals(inside, ImageProcessingTool.readInputSecure(canonicalInput));

        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.writeString(outside.resolve("input.png"), "OUTSIDE", StandardCharsets.UTF_8);
        Path originalParent = inputParent;
        Files.move(inputParent, inputParent.resolveSibling("subdir-original"));
        try {
            Files.createSymbolicLink(originalParent, outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Host cannot create symbolic links: " + e.getMessage());
        }

        assertThrows(IOException.class,
                () -> ImageProcessingTool.readInputSecure(canonicalInput));
    }
}
