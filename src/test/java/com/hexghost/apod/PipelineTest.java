package com.hexghost.apod;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineTest {
    @TempDir Path root;
    @Test void linkedArchiveSkipsVideosAndResumesWithoutRequests() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/first", TestSupport.page("June 18, 1995", TestSupport.image("/one.jpg"), "/video"));
            server.text("/video", TestSupport.page("June 17, 1995", "<iframe src='video'></iframe>", "/last"));
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/two.jpg"), null));
            server.image("/one.jpg", "image one"); server.image("/two.jpg", "image two");
            var config = TestSupport.config(root, "initial-url", server.url("/first"));
            assertEquals(0, new Pipeline(config).run());
            assertEquals("image one", Files.readString(root.resolve("images/one.jpg")));
            int count = server.requests.size();
            assertEquals(0, new Pipeline(config).run()); assertEquals(count, server.requests.size());
            try (var state = new CsvState(root.resolve("state"))) {
                assertEquals(3, state.outcomes().size()); assertEquals("DONE", state.cursorStatus()); assertTrue(state.pending().isEmpty());
                assertEquals("SKIPPED_VIDEO", state.visited(server.url("/video")).status());
            }
        }
    }
    @Test void failedMediaContinuesAndIsOnlyReparsedWhenEnabled() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicBoolean repaired = new AtomicBoolean();
            server.handle("/bad", ex -> TestSupport.Server.reply(ex, 200, "text/html", TestSupport.page("June 17, 1995",
                    repaired.get() ? TestSupport.image("/recovered.jpg") : "<span>missing image</span>", "/last").getBytes()));
            server.text("/last", TestSupport.page("June 16, 1995", "<video src='/video'></video>", null));
            server.image("/recovered.jpg", "recovered");
            var config = TestSupport.config(root, "initial-url", server.url("/bad"));
            assertEquals(1, new Pipeline(config).run()); int count = server.requests.size();
            assertEquals(1, new Pipeline(config).run()); assertEquals(count, server.requests.size());
            repaired.set(true);
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/bad"), "retry-failed", "true")).run());
            assertTrue(Files.exists(root.resolve("images/recovered.jpg")));
        }
    }
    @Test void failedDownloadIsNotRetriedOnNormalRestart() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicBoolean repaired = new AtomicBoolean();
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/image.jpg"), null));
            server.handle("/image.jpg", ex -> {
                if (repaired.get()) TestSupport.Server.reply(ex, 200, "image/jpeg", "OK".getBytes()); else ex.sendResponseHeaders(404, -1);
            });
            var config = TestSupport.config(root, "initial-url", server.url("/last"));
            assertEquals(1, new Pipeline(config).run()); int count = server.requests.size();
            assertEquals(1, new Pipeline(config).run()); assertEquals(count, server.requests.size());
            repaired.set(true);
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/last"), "retry-failed", "true")).run());
        }
    }
    @Test void blockedCursorDoesNotLoopOnRestartOrExplicitRetry() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/bad", "<html>Not a valid APOD page</html>");
            var config = TestSupport.config(root, "initial-url", server.url("/bad"));
            assertEquals(1, new Pipeline(config).run()); assertEquals(1, server.requests.size());
            assertEquals(1, new Pipeline(config).run()); assertEquals(1, server.requests.size());
            assertEquals(1, new Pipeline(TestSupport.config(root, "initial-url", server.url("/bad"), "retry-failed", "true")).run());
            assertEquals(2, server.requests.size());
            try (var state = new CsvState(root.resolve("state"))) {
                assertEquals("BLOCKED", state.cursorStatus()); assertEquals("", state.outcomes().getFirst().date());
            }
        }
    }
    @Test void pageLimitCursorSurvivesOutOfOrderDownloads() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/a", TestSupport.page("June 18, 1995", TestSupport.image("/slow.jpg"), "/b"));
            server.text("/b", TestSupport.page("June 17, 1995", TestSupport.image("/fast.jpg"), "/c"));
            server.text("/c", TestSupport.page("June 16, 1995", "<video></video>", null));
            server.handle("/slow.jpg", ex -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                TestSupport.Server.reply(ex, 200, "image/jpeg", "slow".getBytes());
            });
            server.image("/fast.jpg", "fast");
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/a"), "page-limit", "2")).run());
            try (var state = new CsvState(root.resolve("state"))) { assertEquals(server.url("/c"), state.cursor()); }
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/a"))).run());
            assertEquals(1, server.requests.stream().filter("/a"::equals).count());
        }
    }
    @Test void readyFileAfterCrashIsRecognizedWithoutNetwork() throws Exception {
        var config = TestSupport.config(root);
        Files.createDirectory(root.resolve("images")); Files.writeString(root.resolve("images/photo.jpg"), "complete");
        var ready = new Entry("https://example.test/a", "1995-06-16", "", "https://example.test/photo.jpg", "READY", "photo.jpg",
                Storage.hash(root.resolve("images/photo.jpg")), 8, 1, "", "", "");
        try (var state = new CsvState(root.resolve("state"))) { state.job(ready); state.cursor("", "DONE"); }
        assertEquals(0, new Pipeline(config).run());
        try (var state = new CsvState(root.resolve("state"))) { assertTrue(state.pending().isEmpty()); assertEquals("DOWNLOADED", state.visited(ready.url()).status()); }
    }
    @Test void shutdownPreservesUnfinishedJobsAndCanResume() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicBoolean slow = new AtomicBoolean(true); CountDownLatch started = new CountDownLatch(1);
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/slow.jpg"), null));
            server.handle("/slow.jpg", ex -> {
                started.countDown();
                if (slow.get()) try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                TestSupport.Server.reply(ex, 200, "image/jpeg", "complete".getBytes());
            });
            var pipeline = new Pipeline(TestSupport.config(root, "initial-url", server.url("/last"), "shutdown-grace-period", "50ms"));
            ExecutorService thread = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> result = thread.submit(pipeline::run); assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(3), pipeline::close); result.get(3, TimeUnit.SECONDS);
            } finally { thread.shutdownNow(); }
            try (var state = new CsvState(root.resolve("state"))) { assertEquals(1, state.pending().size()); assertEquals(0, state.failures()); }
            slow.set(false);
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/last"))).run());
        }
    }
    @Test void invalidImageContentIsFailedWithoutFinalFile() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/image.jpg"), null)); server.text("/image.jpg", "not an image");
            assertEquals(1, new Pipeline(TestSupport.config(root, "initial-url", server.url("/last"))).run());
            assertFalse(Files.exists(root.resolve("images/image.jpg")));
        }
    }
    @Test void workersRespectConcurrencyAndQueueBackpressure() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger();
            CountDownLatch bothStarted = new CountDownLatch(2), release = new CountDownLatch(1);
            for (int i = 0; i < 6; i++) {
                String next = i == 5 ? null : "/day" + (i + 1);
                server.text("/day" + i, TestSupport.page("June " + (21 - i) + ", 1995", TestSupport.image("/img" + i + ".jpg"), next));
                server.handle("/img" + i + ".jpg", ex -> {
                    int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max); bothStarted.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                        TestSupport.Server.reply(ex, 200, "image/jpeg", "image".getBytes());
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { active.decrementAndGet(); }
                });
            }
            var pipeline = new Pipeline(TestSupport.config(root, "initial-url", server.url("/day0"), "download-workers", "2", "queue-size", "1"));
            ExecutorService runner = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> result = runner.submit(pipeline::run);
                assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
                // Two jobs active, one queued, and at most one producer blocked on publication.
                Thread.sleep(100);
                assertTrue(server.requests.stream().filter(u -> u.startsWith("/day")).count() <= 4);
                release.countDown(); assertEquals(0, result.get(5, TimeUnit.SECONDS));
                assertEquals(2, maximum.get());
            } finally { release.countDown(); pipeline.close(); runner.shutdownNow(); }
        }
    }
    @Test void lostDownloadDriveLeavesJobsPendingInsteadOfMarkingThemFailed() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/image.jpg"), null));
            server.handle("/image.jpg", ex -> {
                Files.move(root.resolve("images"), root.resolve("disconnected"));
                TestSupport.Server.reply(ex, 200, "image/jpeg", "image".getBytes());
            });
            assertEquals(1, new Pipeline(TestSupport.config(root, "initial-url", server.url("/last"))).run());
            assertFalse(Files.exists(root.resolve("images")));
            try (var state = new CsvState(root.resolve("state"))) {
                assertEquals(0, state.failures()); assertEquals(1, state.pending().size());
            }
        }
    }
    @Test void persistenceFailureStopsDiscoveryAndReportsNonzero() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.handle("/last", ex -> {
                Files.createDirectory(root.resolve("state/visited.csv"));
                TestSupport.Server.reply(ex, 200, "text/html", TestSupport.page("June 16, 1995", "<video></video>", null).getBytes());
            });
            assertEquals(1, new Pipeline(TestSupport.config(root, "initial-url", server.url("/last"))).run());
            assertEquals(1, server.requests.size());
        }
    }
    @Test void missingCompletedFileIsDownloadedAgainOnResume() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/image.jpg"), null)); server.image("/image.jpg", "complete");
            var config = TestSupport.config(root, "initial-url", server.url("/last"));
            assertEquals(0, new Pipeline(config).run()); Files.delete(root.resolve("images/image.jpg"));
            assertEquals(0, new Pipeline(config).run());
            assertEquals(2, server.requests.stream().filter("/image.jpg"::equals).count());
            assertEquals(1, server.requests.stream().filter("/last"::equals).count());
        }
    }
    @Test void backwardTraversalRejectsForwardDates() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/a", TestSupport.page("June 17, 1995", "<video></video>", "/b"));
            server.text("/b", TestSupport.page("June 18, 1995", "<video></video>", "/a"));
            assertEquals(1, new Pipeline(TestSupport.config(root, "initial-url", server.url("/a"))).run());
            assertEquals(2, server.requests.size());
            try (var state = new CsvState(root.resolve("state"))) { assertEquals("BLOCKED", state.cursorStatus()); }
        }
    }

    @Test void repeatedImageAcrossDaysIsTransferredOnce() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.text("/a", TestSupport.page("June 17, 1995", TestSupport.image("/shared.jpg"), "/b"));
            server.text("/b", TestSupport.page("June 16, 1995", TestSupport.image("/shared.jpg"), null));
            server.handle("/shared.jpg", ex -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                TestSupport.Server.reply(ex, 200, "image/jpeg", "shared".getBytes());
            });
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/a"))).run());
            assertEquals(1, server.requests.stream().filter("/shared.jpg"::equals).count());
            try (var state = new CsvState(root.resolve("state"))) {
                assertEquals(2, state.outcomes().size());
                assertTrue(state.outcomes().stream().allMatch(e -> e.status().equals("DOWNLOADED") && e.localPath().equals("shared.jpg")));
            }
        }
    }
    @Test void retriedDownloadFailureIsNotAttemptedTwiceAtExplicitFrontier() throws Exception {
        try (var server = new TestSupport.Server()) {
            String pageUrl = server.url("/last"), imageUrl = server.url("/image.jpg");
            server.text("/last", TestSupport.page("June 16, 1995", TestSupport.image("/image.jpg"), null));
            server.handle("/image.jpg", ex -> ex.sendResponseHeaders(404, -1));
            var entry = new Entry(pageUrl, "1995-06-16", "", imageUrl, "FAILED", "", "", 0, 1, "DOWNLOAD", "", "HTTP 404");
            try (var state = new CsvState(root.resolve("state"))) { state.finish(entry); state.cursor("", "DONE"); }
            assertEquals(1, new Pipeline(TestSupport.config(root, "start-url", pageUrl, "retry-failed", "true")).run());
            assertEquals(1, server.requests.stream().filter("/image.jpg"::equals).count());
            assertEquals(0, server.requests.stream().filter("/last"::equals).count());
            try (var state = new CsvState(root.resolve("state"))) { assertEquals(1, state.failures()); assertTrue(state.pending().isEmpty()); }
        }
    }
    @Test void dateAliasesAreNotDownloadedTwice() throws Exception {
        try (var server = new TestSupport.Server()) {
            String media = TestSupport.image("/image.jpg");
            server.text("/original", TestSupport.page("June 16, 1995", media, null)); server.image("/image.jpg", "image");
            server.text("/alias", TestSupport.page("June 16, 1995", media, null));
            assertEquals(0, new Pipeline(TestSupport.config(root, "initial-url", server.url("/original"))).run());
            assertEquals(0, new Pipeline(TestSupport.config(root, "start-url", server.url("/alias"))).run());
            assertEquals(1, server.requests.stream().filter("/image.jpg"::equals).count());
        }
    }

}
