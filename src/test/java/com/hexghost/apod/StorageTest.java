package com.hexghost.apod;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path root;
    @Test void absoluteAndRelativeDirectoriesWorkButMissingMountDoesNot() throws Exception {
        new Storage(root.resolve("images"));
        assertTrue(Files.isDirectory(root.resolve("images")));
        assertThrows(Storage.Failure.class, () -> new Storage(root.resolve("missing-mount/images")));
        Path relative = Path.of("").toAbsolutePath().relativize(root.resolve("relative"));
        new Storage(relative); assertTrue(Files.isDirectory(root.resolve("relative")));
    }
    @Test void replacedOrMissingDestinationIsRejected() throws Exception {
        Path images = root.resolve("images"); var storage = new Storage(images);
        Files.move(images, root.resolve("unmounted")); Files.createDirectory(images);
        assertThrows(Storage.Failure.class, storage::check);
    }
    @Test void filenameAndStoredPathsAreSafe() throws Exception {
        var storage = new Storage(root.resolve("images"));
        assertEquals("image.jpg", Storage.filename("https://example.test/image.jpg?width=100"));
        assertThrows(java.io.IOException.class, () -> Storage.filename("https://example.test/.."));
        assertThrows(java.io.IOException.class, () -> Storage.filename("https://example.test/a%5Cb.jpg"));
        assertThrows(Storage.Failure.class, () -> storage.resolve("../outside"));
        Files.createSymbolicLink(root.resolve("images/link"), root);
        assertThrows(Storage.Failure.class, () -> storage.resolve("link/outside"));
    }
    @Test void distinctImagesWithSameFilenameKeepOriginalNames() throws Exception {
        var storage = new Storage(root.resolve("images"));
        try (var state = new CsvState(root.resolve("state"))) {
            Entry first = new Entry("https://example.test/a", "2019-06-24", "", "https://example.test/a/photo.jpg", "PENDING", "", "", 0, 0, "", "", "");
            Entry second = new Entry("https://example.test/b", "2019-06-23", "", "https://example.test/b/photo.jpg", "PENDING", "", "", 0, 0, "", "", "");
            assertEquals("photo.jpg", storage.reserve(first, state).localPath());
            assertEquals("2019-06-23/photo.jpg", storage.reserve(second, state).localPath());
        }
    }
    @Test void digestBasedRecoveryWorksAfterRelocatingCollection() throws Exception {
        Path images = root.resolve("images"); var storage = new Storage(images);
        Files.writeString(images.resolve("photo.jpg"), "complete");
        var entry = Entry.unknown("https://example.test/a").file("photo.jpg", Storage.hash(images.resolve("photo.jpg")), 8, "READY");
        assertTrue(storage.verified(entry));
        Files.move(images, root.resolve("drive"));
        assertTrue(new Storage(root.resolve("drive")).verified(entry));
        Files.writeString(root.resolve("drive/photo.jpg"), "corrupt!");
        assertFalse(new Storage(root.resolve("drive")).verified(entry));
    }
}
