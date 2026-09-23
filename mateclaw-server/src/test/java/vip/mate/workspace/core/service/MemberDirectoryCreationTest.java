package vip.mate.workspace.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MemberDirectoryCreationTest {
    @TempDir Path directory;

    @Test void directoryCreationUsesOpenedParentWhenPathBecomesPeerSymlink() throws Exception {
        Path root = Files.createDirectory(directory.resolve("member"));
        Path original = Files.createDirectory(root.resolve("nested"));
        Path peer = Files.createDirectory(directory.resolve("peer"));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(original)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>, "Linux SecureDirectoryStream required");
            @SuppressWarnings("unchecked") var handle = (SecureDirectoryStream<Path>) stream;
            Path moved = root.resolve("moved");
            Files.move(original, moved);
            Files.createSymbolicLink(original, peer);
            MemberFileAccess.createDirectory(root, handle, Path.of("new-child"));
            assertTrue(Files.isDirectory(moved.resolve("new-child")));
            assertFalse(Files.exists(peer.resolve("new-child")));
        }
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".member-directory-")));
        }
    }
}
