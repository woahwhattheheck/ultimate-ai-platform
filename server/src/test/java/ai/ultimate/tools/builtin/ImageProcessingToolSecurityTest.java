package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolSecurityTest {
    @TempDir
    Path temporary;

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
