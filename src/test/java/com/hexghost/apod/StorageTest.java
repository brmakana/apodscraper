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
    @Test void existingFilenameIsSkippedWithoutDownloadingOrCreatingDateFolder() throws Exception {
        var storage = new Storage(root.resolve("images"));
        Files.writeString(root.resolve("images/photo.jpg"), "already saved");
        try (var server = new TestSupport.Server(); var state = new CsvState(root.resolve("state"));
             var http = new HttpFetcher(TestSupport.config(root), new RequestScheduler(java.time.Duration.ZERO, 1000))) {
            Entry job = new Entry(server.url("/day"), "2019-06-23", "", server.url("/other/photo.jpg"), "PENDING", "", "", 0, 0, "", "", "");
            state.job(job);
            Entry result = storage.download(job, state, http);
            assertEquals("SKIPPED_DUPLICATE", result.status());
            assertEquals("photo.jpg", result.localPath()); assertTrue(storage.verified(result));
            assertEquals(result, state.visited(job.url())); assertTrue(state.pending().isEmpty());
            assertTrue(server.requests.isEmpty());
            assertFalse(Files.exists(root.resolve("images/2019-06-23")));
            assertEquals("already saved", Files.readString(root.resolve("images/photo.jpg")));
        }
    }
    @Test void legacyDatedSavedImageIsRecognizedByUrlWithoutSavingAnotherCopy() throws Exception {
        var storage = new Storage(root.resolve("images"));
        Files.createDirectory(root.resolve("images/2019-06-24"));
        Path legacy = root.resolve("images/2019-06-24/photo.jpg"); Files.writeString(legacy, "original");
        try (var server = new TestSupport.Server(); var state = new CsvState(root.resolve("state"));
             var http = new HttpFetcher(TestSupport.config(root), new RequestScheduler(java.time.Duration.ZERO, 1000))) {
            Entry saved = new Entry(server.url("/first"), "2019-06-24", "", server.url("/photo.jpg"), "DOWNLOADED",
                    "2019-06-24/photo.jpg", Storage.hash(legacy), Files.size(legacy), 1, "", "", "");
            state.finish(saved);
            Entry job = new Entry(server.url("/second"), "2019-06-23", "", saved.image(), "PENDING", "", "", 0, 0, "", "", "");
            Entry result = storage.download(job, state, http);
            assertEquals("SKIPPED_DUPLICATE", result.status()); assertEquals(saved.localPath(), result.localPath());
            assertTrue(server.requests.isEmpty()); assertFalse(Files.exists(root.resolve("images/photo.jpg")));
            assertTrue(Files.exists(legacy));
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
