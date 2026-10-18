package com.hexghost.apod;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.HexFormat;

final class Storage {
    static final class Failure extends IOException {
        Failure(String message, Throwable cause) { super(message, cause); }
    }
    private final Path root;
    private final Object rootKey;
    private final FileStore store;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> filenameLocks = new java.util.concurrent.ConcurrentHashMap<>();
    Storage(Path configured) throws IOException {
        root = configured.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.getParent())) throw new Failure("Download parent must already exist (is the drive mounted?): " + root.getParent(), null);
        if (!Files.exists(root)) Files.createDirectory(root);
        if (!Files.isDirectory(root) || !Files.isWritable(root)) throw new Failure("Download directory is not writable: " + root, null);
        rootKey = Files.readAttributes(root, BasicFileAttributes.class).fileKey();
        store = Files.getFileStore(root);
        Path probe = Files.createTempFile(root, ".apod-probe-", ".tmp"); Files.delete(probe);
    }
    void check() throws Failure {
        try {
            var attrs = Files.readAttributes(root, BasicFileAttributes.class);
            if (!attrs.isDirectory() || !java.util.Objects.equals(rootKey, attrs.fileKey()) || !store.equals(Files.getFileStore(root)))
                throw new IOException("Download directory identity changed");
        } catch (IOException e) { throw new Failure("Download storage unavailable: " + root, e); }
    }
    Path resolve(String relative) throws Failure {
        Path path = root.resolve(relative).normalize();
        if (Path.of(relative).isAbsolute() || !path.startsWith(root) || path.equals(root))
            throw new Failure("Unsafe image path: " + relative, null);
        // Never traverse a symlink supplied through an existing image/date directory.
        for (Path part = path; part != null && !part.equals(root); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new Failure("Image path contains a symbolic link: " + part, null);
        return path;
    }
    static String filename(String image) throws IOException {
        String path = URI.create(image).getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("\\")
                || name.chars().anyMatch(c -> c < 32 || c == 127) || name.contains(":"))
            throw new HttpFetcher.FetchFailure("Unsafe/missing image filename: " + image, 1, null);
        return name;
    }
    boolean verified(Entry e) throws IOException {
        check();
        if (e.localPath().isBlank() || e.checksum().isBlank()) return false;
        Path file = resolve(e.localPath());
        return Files.isRegularFile(file) && Files.size(file) == e.bytes() && hash(file).equals(e.checksum());
    }
    Entry download(Entry job, CsvState state, HttpFetcher http) throws IOException, InterruptedException {
        // Different URLs can share a filename. The first saved file wins, and waiting
        // workers skip it instead of racing a final move or creating date directories.
        synchronized (filenameLocks.computeIfAbsent(filename(job.image()), ignored -> new Object())) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return downloadLocked(job, state, http);
        }
    }
    private Entry downloadLocked(Entry job, CsvState state, HttpFetcher http) throws IOException, InterruptedException {
        // A matching file saved by this very job before a crash is still a download completion.
        if (verified(job)) return finish(state, job.outcome("DOWNLOADED", "", job.attempts(), ""));
        // Saved images from legacy date subdirectories can also establish a duplicate URL.
        for (Entry existing : state.outcomes()) {
            if ((existing.status().equals("DOWNLOADED") || existing.status().equals("SKIPPED_DUPLICATE"))
                    && existing.image().equals(job.image()) && verified(existing)) {
                return finish(state, job.file(existing.localPath(), existing.checksum(), existing.bytes(), "SKIPPED_DUPLICATE")
                        .outcome("SKIPPED_DUPLICATE", "", 0, ""));
            }
        }
        String name = filename(job.image());
        Entry duplicate = skipExisting(job, name, 0, state);
        if (duplicate != null) return duplicate;
        check();
        Entry reserved = job.file(name, "", 0, "PENDING");
        state.job(reserved);
        Path temp;
        try { temp = Files.createTempFile(root, ".apod-", ".part"); }
        catch (IOException e) { throw new Failure("Cannot create temporary image on download drive", e); }
        try {
            var result = http.image(URI.create(job.image()), temp);
            check();
            String contentType = result.response().headers().firstValue("Content-Type").orElse("");
            if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
                throw new HttpFetcher.FetchFailure("Expected image content type, got " + contentType, result.attempts(), null);
            long size;
            try { size = Files.size(temp); }
            catch (IOException e) { throw new Failure("Cannot inspect downloaded image", e); }
            long expected = result.response().headers().firstValueAsLong("Content-Length").orElse(-1);
            if (size == 0 || (expected >= 0 && expected != size))
                throw new HttpFetcher.FetchFailure("Empty or truncated image response", result.attempts(), null);
            try {
                String digest = hash(temp);
                try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
                Entry ready = reserved.file(reserved.localPath(), digest, size, "READY")
                        .outcome("READY", "", result.attempts(), "");
                state.job(ready); // Recoverable if the process dies immediately after the move.
                try { Files.move(temp, resolve(ready.localPath())); } // Never replace an existing image.
                catch (FileAlreadyExistsException exists) {
                    Entry lateDuplicate = skipExisting(job, name, result.attempts(), state);
                    if (lateDuplicate != null) return lateDuplicate;
                    throw exists;
                }
                return finish(state, ready.outcome("DOWNLOADED", "", result.attempts(), ""));
            } catch (IOException e) { throw new Failure("Cannot finalize downloaded image", e); }
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* Preserve original failure. */ }
        }
    }
    private Entry skipExisting(Entry job, String path, int attempts, CsvState state) throws IOException {
        check();
        Path file = resolve(path);
        if (!Files.exists(file)) return null;
        if (!Files.isRegularFile(file)) throw new Failure("Image destination is not a regular file: " + file, null);
        // Filename collisions are deliberately treated as duplicates without fetching the
        // remote image to compare contents. Retain the first file and record its metadata.
        return finish(state, job.file(path, hash(file), Files.size(file), "SKIPPED_DUPLICATE")
                .outcome("SKIPPED_DUPLICATE", "", attempts, ""));
    }
    private Entry finish(CsvState state, Entry entry) throws IOException {
        state.finish(entry);
        return entry;
    }
    static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    void cleanPartials() throws IOException {
        check();
        try (var paths = Files.newDirectoryStream(root, ".apod-*.part")) {
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
}
