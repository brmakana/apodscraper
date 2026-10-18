package com.hexghost.apod;

import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.*;

/** All state transitions are serialized; each CSV replacement is atomic on supported filesystems. */
final class CsvState implements AutoCloseable {
    private static final String[] HEADER = {"record_type", "date", "page_url", "status", "image_url", "local_path",
            "previous_page_url", "checksum", "bytes", "failure_stage", "attempts", "last_attempt_at", "last_error"};
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<String, Entry> visited = new LinkedHashMap<>(), work = new LinkedHashMap<>();
    private String cursor = "";
    private String cursorStatus = "NEW";

    CsvState(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("State directory is in use: " + directory);
            lock = acquired;
            load(this.directory.resolve("visited.csv"), visited, false);
            load(this.directory.resolve("work.csv"), work, true);
            // A visited outcome is authoritative if a crash left a stale work row.
            work.keySet().removeAll(visited.keySet());
            saveWork();
        } catch (IOException | RuntimeException e) {
            if (acquired != null) acquired.release();
            lockChannel.close();
            throw e;
        }
    }
    synchronized String cursor() { return cursor; }
    synchronized String cursorStatus() { return cursorStatus; }
    synchronized Entry visited(String url) { return visited.get(url); }
    synchronized boolean hasWork(String url) { return work.containsKey(url); }
    synchronized List<Entry> pending() { return List.copyOf(work.values()); }
    synchronized List<Entry> outcomes() { return List.copyOf(visited.values()); }
    synchronized long failures() { return visited.values().stream().filter(Entry::failed).count(); }
    synchronized void cursor(String url, String status) throws IOException {
        cursor = url; cursorStatus = status; saveWork();
    }
    synchronized void job(Entry entry) throws IOException { work.put(entry.url(), entry); saveWork(); }
    synchronized void finish(Entry entry) throws IOException {
        visited.put(entry.url(), entry);
        save(directory.resolve("visited.csv"), visited, false);
        work.remove(entry.url()); saveWork();
    }
    synchronized void retry(Entry entry) throws IOException {
        // Save pending intent first; a visited FAILED record wins until its removal is durable.
        work.put(entry.url(), entry.outcome("PENDING", "", 0, "")); saveWork();
        visited.remove(entry.url()); save(directory.resolve("visited.csv"), visited, false);
    }
    synchronized boolean dateExists(Entry entry) {
        if (entry.date().isEmpty()) return false;
        return java.util.stream.Stream.concat(visited.values().stream(), work.values().stream())
                .anyMatch(other -> other.date().equals(entry.date()) && !other.url().equals(entry.url()));
    }
    synchronized boolean pathOwned(String path, String image) {
        return java.util.stream.Stream.concat(visited.values().stream(), work.values().stream())
                .anyMatch(e -> e.localPath().equals(path) && !e.image().equals(image));
    }
    private void saveWork() throws IOException { save(directory.resolve("work.csv"), work, true); }
    private void save(Path target, Map<String, Entry> entries, boolean includeCursor) throws IOException {
        Path temp = Files.createTempFile(directory, ".state-", ".csv.tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp); var csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(HEADER).get())) {
                if (includeCursor) csv.printRecord("CURSOR", "", cursor, cursorStatus, "", "", "", "", 0, "", 0, "", "");
                for (Entry e : entries.values()) csv.printRecord("PAGE", e.date(), e.url(), e.status(), e.image(), e.localPath(),
                        e.previous(), e.checksum(), e.bytes(), e.stage(), e.attempts(), e.at(), e.error());
            }
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            replace(temp, target);
        } finally { Files.deleteIfExists(temp); }
    }
    static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private void load(Path file, Map<String, Entry> into, boolean isWork) throws IOException {
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file);
             var csv = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            if (!csv.getHeaderNames().equals(List.of(HEADER))) throw new IOException("Unexpected CSV schema: " + file);
            boolean cursorSeen = false;
            for (var row : csv) {
                if (!row.isConsistent()) throw new IOException("Wrong field count at row " + row.getRecordNumber());
                if (row.get("record_type").equals("CURSOR") && isWork) {
                    if (cursorSeen) throw new IOException("Duplicate crawl cursor");
                    cursorSeen = true; cursor = row.get("page_url"); cursorStatus = row.get("status");
                    if (!Set.of("NEW", "ACTIVE", "BLOCKED", "DONE").contains(cursorStatus)) throw new IOException("Invalid cursor status");
                    if (!cursor.isEmpty()) Urls.validate(URI.create(cursor));
                    continue;
                }
                if (!row.get("record_type").equals("PAGE")) throw new IOException("Unexpected record type");
                String url = Urls.canonical(URI.create(row.get("page_url")));
                var e = new Entry(url, row.get("date"), row.get("previous_page_url"), row.get("image_url"), row.get("status"),
                        row.get("local_path"), row.get("checksum"), Long.parseLong(row.get("bytes")),
                        Integer.parseInt(row.get("attempts")), row.get("failure_stage"), row.get("last_attempt_at"), row.get("last_error"));
                e.day();
                if (!e.previous().isEmpty()) Urls.validate(URI.create(e.previous()));
                if (!e.image().isEmpty()) Urls.validate(URI.create(e.image()));
                if (!e.localPath().isEmpty() && (Path.of(e.localPath()).isAbsolute() || Path.of(e.localPath()).normalize().startsWith("..")))
                    throw new IOException("Unsafe local image path in CSV");
                if (!(isWork ? Set.of("PENDING", "READY") : Set.of("DOWNLOADED", "SKIPPED_VIDEO", "FAILED")).contains(e.status()))
                    throw new IOException("Invalid page status: " + e.status());
                if (e.bytes() < 0 || e.attempts() < 0) throw new IOException("Negative counts in CSV");
                if (into.putIfAbsent(url, e) != null) throw new IOException("Duplicate page URL: " + url);
            }
        } catch (RuntimeException e) { throw new IOException("Malformed CSV " + file + ": " + e.getMessage(), e); }
    }
    @Override public synchronized void close() throws IOException { lock.release(); lockChannel.close(); }
}
