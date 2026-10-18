package com.hexghost.apod;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CsvStateTest {
    @TempDir Path root;
    @Test void quotedErrorsAndCursorRoundTripAndPendingReplay() throws Exception {
        var entry = Entry.unknown("https://example.test/a").outcome("FAILED", "PARSE", 1, "Comma, quote \" and\nnewline");
        try (var state = new CsvState(root)) {
            state.finish(entry); state.job(Entry.unknown("https://example.test/b")); state.cursor("https://example.test/c", "ACTIVE");
        }
        try (var state = new CsvState(root)) {
            assertEquals(entry, state.visited(entry.url())); assertEquals(1, state.pending().size());
            assertEquals("https://example.test/c", state.cursor());
        }
    }
    @Test void staleWorkCannotOverrideFailedOutcome() throws Exception {
        try (var state = new CsvState(root)) {
            state.job(Entry.unknown("https://example.test/a"));
            Path stale = root.resolve("stale.csv"); Files.copy(root.resolve("work.csv"), stale);
            state.finish(Entry.unknown("https://example.test/a").outcome("FAILED", "FETCH", 3, "bad"));
            Files.copy(stale, root.resolve("work.csv"), StandardCopyOption.REPLACE_EXISTING);
        }
        try (var state = new CsvState(root)) { assertTrue(state.pending().isEmpty()); assertEquals(1, state.failures()); }
    }
    @Test void explicitRetryMovesFailedOutcomeBackToWork() throws Exception {
        var entry = Entry.unknown("https://example.test/a");
        try (var state = new CsvState(root)) { state.finish(entry.outcome("FAILED", "FETCH", 1, "bad")); state.retry(entry); }
        try (var state = new CsvState(root)) { assertNull(state.visited(entry.url())); assertEquals(1, state.pending().size()); }
    }
    @Test void malformedCsvAndDuplicatesAreRejected() throws Exception {
        Files.writeString(root.resolve("visited.csv"), "wrong,header\na,b\n");
        assertThrows(java.io.IOException.class, () -> new CsvState(root));
        Files.delete(root.resolve("visited.csv"));
        try (var state = new CsvState(root)) { state.finish(Entry.unknown("https://example.test/a").outcome("FAILED", "PARSE", 1, "bad")); }
        var lines = Files.readAllLines(root.resolve("visited.csv"));
        Files.writeString(root.resolve("visited.csv"), String.join("\n", lines) + "\n" + lines.get(1) + "\n");
        assertThrows(java.io.IOException.class, () -> new CsvState(root));
    }
    @Test void secondProcessLockIsRejected() throws Exception {
        try (var state = new CsvState(root)) { assertThrows(Exception.class, () -> new CsvState(root)); }
    }
    @Test void skippedDuplicateRoundTripsAndRetiresStaleWork() throws Exception {
        Entry duplicate = Entry.unknown("https://example.test/duplicate")
                .file("photo.jpg", "saved digest", 20, "SKIPPED_DUPLICATE")
                .outcome("SKIPPED_DUPLICATE", "", 0, "");
        Path stale = root.resolve("stale.csv");
        try (var state = new CsvState(root)) {
            state.job(Entry.unknown(duplicate.url())); Files.copy(root.resolve("work.csv"), stale);
            state.finish(duplicate);
        }
        Files.copy(stale, root.resolve("work.csv"), StandardCopyOption.REPLACE_EXISTING);
        try (var state = new CsvState(root)) {
            assertEquals(duplicate, state.visited(duplicate.url())); assertTrue(state.pending().isEmpty());
            assertEquals(0, state.failures());
        }
    }
}
