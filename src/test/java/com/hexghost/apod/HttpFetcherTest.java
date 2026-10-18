package com.hexghost.apod;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpFetcherTest {
    @TempDir Path root;
    @Test void retriesAndRedirectsGoThroughSameLimiter() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicInteger calls = new AtomicInteger();
            server.handle("/redirect", ex -> { ex.getResponseHeaders().set("Location", "/target"); ex.sendResponseHeaders(302, -1); });
            server.handle("/target", ex -> TestSupport.Server.reply(ex, calls.incrementAndGet() == 1 ? 503 : 200, "text/html", "OK".getBytes()));
            AtomicLong now = new AtomicLong();
            var config = TestSupport.config(root);
            var limiter = new RequestScheduler(Duration.ofMillis(50), 20, now::get, now::addAndGet);
            try (var http = new HttpFetcher(config, limiter)) {
                var result = http.html(URI.create(server.url("/redirect")));
                assertEquals("OK", result.response().body()); assertEquals(2, result.attempts());
                assertEquals(4, server.requests.size()); assertEquals(150_000_000L, now.get());
            }
        }
    }
    @Test void rateLimitedResponsePausesSharedScheduler() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicInteger calls = new AtomicInteger(); AtomicLong now = new AtomicLong();
            server.handle("/target", ex -> {
                if (calls.incrementAndGet() == 1) { ex.getResponseHeaders().set("Retry-After", "2"); ex.sendResponseHeaders(429, -1); }
                else TestSupport.Server.reply(ex, 200, "text/html", "OK".getBytes());
            });
            var scheduler = new RequestScheduler(Duration.ZERO, 20, now::get, now::addAndGet);
            try (var http = new HttpFetcher(TestSupport.config(root), scheduler)) { http.html(URI.create(server.url("/target"))); }
            assertEquals(2_000_000_000L, now.get());
        }
    }
    @Test void permanentFailureIsNotRetried() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.handle("/missing", ex -> ex.sendResponseHeaders(404, -1));
            try (var http = new HttpFetcher(TestSupport.config(root), new RequestScheduler(Duration.ZERO, 1000))) {
                var error = assertThrows(HttpFetcher.FetchFailure.class, () -> http.html(URI.create(server.url("/missing"))));
                assertEquals(1, error.attempts); assertEquals(1, server.requests.size());
            }
        }
    }
    @Test void stalledResponseBodyTimesOut() throws Exception {
        try (var server = new TestSupport.Server()) {
            server.handle("/slow", ex -> {
                ex.sendResponseHeaders(200, 100); ex.getResponseBody().write(1); ex.getResponseBody().flush();
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            var config = TestSupport.config(root, "request-timeout", "150ms", "max-attempts", "1");
            try (var http = new HttpFetcher(config, new RequestScheduler(Duration.ZERO, 1000))) {
                Path file = Files.createTempFile(root, "image", ".part");
                assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                        assertThrows(HttpFetcher.FetchFailure.class, () -> http.image(URI.create(server.url("/slow")), file)));
            }
        }
    }
    @Test void retryTruncatesPartialImageFile() throws Exception {
        try (var server = new TestSupport.Server()) {
            AtomicInteger calls = new AtomicInteger();
            server.handle("/image", ex -> {
                ex.getResponseHeaders().set("Content-Type", "image/jpeg");
                if (calls.incrementAndGet() == 1) { ex.sendResponseHeaders(200, 1000); ex.getResponseBody().write(new byte[100]); }
                else TestSupport.Server.reply(ex, 200, "image/jpeg", "small".getBytes());
            });
            try (var http = new HttpFetcher(TestSupport.config(root), new RequestScheduler(Duration.ZERO, 1000))) {
                Path file = Files.createTempFile(root, "image", ".part");
                http.image(URI.create(server.url("/image")), file);
                assertEquals("small", Files.readString(file)); assertEquals(2, calls.get());
            }
        }
    }
}
