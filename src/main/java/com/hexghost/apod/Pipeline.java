package com.hexghost.apod;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Pipeline implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Pipeline.class);
    private final ApodProperties config;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicReference<Throwable> fatal = new AtomicReference<>();
    private final CountDownLatch finished = new CountDownLatch(1);
    private final BlockingQueue<Entry> queue;
    private final Map<String, Entry> scheduled = new ConcurrentHashMap<>();
    private final Set<String> attemptedPages = new HashSet<>();
    private volatile Thread crawler;
    private volatile boolean discoveryFinished;
    private volatile int exitCode;
    Pipeline(ApodProperties config) { this.config = config; queue = new ArrayBlockingQueue<>(config.queueSize()); }
    int exitCode() { return exitCode; }

    int run() {
        crawler = Thread.currentThread();
        ExecutorService workers = Executors.newFixedThreadPool(config.downloadWorkers());
        try (CsvState state = new CsvState(config.stateDirectory());
             HttpFetcher http = new HttpFetcher(config, new RequestScheduler(config.requestInterval(), config.rateLimitPerSecond()))) {
            Storage storage = new Storage(config.downloadDirectory());
            storage.cleanPartials();
            recover(state, storage);
            for (int i = 0; i < config.downloadWorkers(); i++) workers.submit(() -> worker(state, storage, http));
            try {
                for (Entry e : state.pending()) if (!e.image().isEmpty()) publish(e);
                String start = chooseStart(state);
                // Retry failed page parsing independently of the current crawl frontier.
                if (config.retryFailed()) for (Entry e : state.outcomes()) {
                    if (e.failed() && e.image().isEmpty() && !stopping.get()) process(e.url(), null, state, storage, http);
                }
                String current = Urls.archiveUrl(start);
                Set<String> seen = new HashSet<>();
                LocalDate lastDay = null;
                int pages = 0;
                while (current != null && !current.isEmpty() && !stopping.get() && fatal.get() == null
                        && (config.pageLimit() == 0 || pages < config.pageLimit())) {
                    storage.check();
                    if (!seen.add(current)) {
                        log.error("Previous-page navigation cycle at {}", current);
                        state.cursor(current, "BLOCKED"); break;
                    }
                    state.cursor(current, "ACTIVE");
                    Entry result = process(current, lastDay, state, storage, http);
                    pages++;
                    if (result == null) break;
                    if (result.day() != null) lastDay = result.day();
                    current = Urls.archiveUrl(result.previous());
                    if (current.isEmpty()) state.cursor("", "DONE");
                    else state.cursor(current, "ACTIVE");
                }
            } catch (InterruptedException e) {
                stopping.set(true); // Cleared here so the grace-period wait can still drain workers.
            } finally {
                discoveryFinished = true;
                workers.shutdown();
                try {
                    if (!workers.awaitTermination(config.shutdownGracePeriod().toMillis(), TimeUnit.MILLISECONDS)) {
                        workers.shutdownNow();
                        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) fatal.compareAndSet(null, new IOException("Download workers did not stop"));
                    }
                } catch (InterruptedException e) {
                    workers.shutdownNow();
                    try {
                        if (!workers.awaitTermination(5, TimeUnit.SECONDS)) fatal.compareAndSet(null, new IOException("Download workers did not stop"));
                    } catch (InterruptedException again) { fatal.compareAndSet(null, again); }
                }
            }
            if (fatal.get() != null) throw new IOException("Pipeline stopped: " + fatal.get().getMessage(), fatal.get());
            exitCode = state.failures() > 0 || state.cursorStatus().equals("BLOCKED") ? 1 : 0;
            long downloaded = state.outcomes().stream().filter(e -> e.status().equals("DOWNLOADED")).count();
            long skipped = state.outcomes().stream().filter(e -> e.status().equals("SKIPPED_VIDEO")).count();
            long duplicates = state.outcomes().stream().filter(e -> e.status().equals("SKIPPED_DUPLICATE")).count();
            log.info("Finished: {} downloaded, {} videos skipped, {} duplicates skipped, {} failed, {} pending; cursor {} {}",
                    downloaded, skipped, duplicates, state.failures(), state.pending().size(), state.cursorStatus(), state.cursor());
        } catch (Exception e) {
            exitCode = 1; log.error("APOD scrape stopped: {}", e.getMessage(), e);
        } finally {
            discoveryFinished = true; workers.shutdownNow(); finished.countDown(); crawler = null;
        }
        return exitCode;
    }
    private void recover(CsvState state, Storage storage) throws IOException {
        for (Entry e : state.outcomes()) {
            if (e.status().equals("DOWNLOADED") && !storage.verified(e)) state.retry(e);
            else if (e.failed() && config.retryFailed() && !e.image().isEmpty()) state.retry(e);
        }
        for (Entry e : state.pending())
            if (e.status().equals("READY") && storage.verified(e)) state.finish(e.outcome("DOWNLOADED", "", e.attempts(), ""));
    }
    private String chooseStart(CsvState state) throws IOException {
        if (config.startUrl() != null) return Urls.canonical(config.startUrl());
        if (state.cursorStatus().equals("DONE")) return "";
        if (state.cursorStatus().equals("BLOCKED") && !config.retryFailed()
                && !wrongArchiveDate(state.cursor(), state.visited(state.cursor()))) {
            log.error("Crawl blocked at {}; enable apod.retry-failed or provide apod.start-url", state.cursor()); return "";
        }
        if (!state.cursor().isEmpty()) return state.cursor();
        return state.outcomes().stream().filter(e -> e.day() != null)
                .min(Comparator.comparing(Entry::day)).map(Entry::url).orElse(Urls.canonical(config.initialUrl()));
    }
    private Entry process(String url, LocalDate lastDay, CsvState state, Storage storage, HttpFetcher http)
            throws IOException, InterruptedException {
        url = Urls.archiveUrl(url);
        Entry known = state.visited(url);
        if (wrongArchiveDate(url, known)) known = null;
        // The job may have completed or failed while discovery was catching up with replayed work.
        // Reuse its navigation metadata without attempting that job again in this run.
        if (scheduled.containsKey(url)) known = scheduled.get(url);
        if (known != null && (!known.failed() || !config.retryFailed() || attemptedPages.contains(url))) {
            if (known.previous().isEmpty() && !LocalDate.of(1995, 6, 16).equals(known.day())) {
                state.cursor(url, "BLOCKED"); return null;
            }
            return known;
        }
        PageParser.Page page;
        int attempts;
        attemptedPages.add(url);
        try {
            var fetched = http.html(URI.create(url)); attempts = fetched.attempts();
            page = new PageParser().parse(fetched.response().body(), fetched.response().uri(), URI.create(url));
        } catch (HttpFetcher.FetchFailure e) {
            state.finish(Entry.unknown(url).outcome("FAILED", "FETCH", e.attempts, e.getMessage()));
            state.cursor(url, "BLOCKED"); log.warn("Page failed: {}: {}", url, e.getMessage()); return null;
        }
        // Key the record by the requested canonical URL, preserving deduplication through redirects.
        Entry entry = new Entry(url, page.date() == null ? "" : page.date().toString(), page.previous(), page.image(),
                "PENDING", "", "", 0, attempts, "", "", "");
        String error = page.error();
        boolean invalidOrder = lastDay != null && page.date() != null && !page.date().isBefore(lastDay);
        if (invalidOrder) error = "Previous-page date did not move backward";
        if (!error.isEmpty()) {
            // Discard partial media discovery: a parser failure must be reparsed on explicit retry.
            Entry failed = new Entry(entry.url(), entry.date(), entry.previous(), "", "FAILED", "", "", 0,
                    attempts, "PARSE", java.time.Instant.now().toString(), error);
            state.finish(failed); log.warn("Page failed: {}: {}", url, error);
            if (entry.previous().isEmpty() || entry.previous().equals(url) || invalidOrder) {
                state.cursor(url, "BLOCKED"); return null;
            }
            return failed;
        }
        if (state.dateExists(entry)) {
            log.info("Already tracked APOD day {} at another URL; following navigation from {}", entry.date(), url);
            return entry;
        }
        if (page.video()) {
            state.finish(entry.outcome("SKIPPED_VIDEO", "", attempts, "")); log.info("Skipped video {} {}", entry.date(), url);
        } else if (!state.hasWork(url)) {
            if (known != null && known.failed()) state.retry(entry);
            else state.job(entry);
            publish(entry); log.info("Queued {} {}", entry.date(), entry.image());
        }
        return entry;
    }
    private boolean wrongArchiveDate(String url, Entry entry) {
        LocalDate expected = Urls.date(URI.create(url));
        return entry != null && entry.failed() && expected != null
                && !expected.isAfter(Urls.LEGACY_CUTOFF) && !expected.isBefore(Urls.FIRST_DAY)
                && !expected.equals(entry.day());
    }
    private void publish(Entry entry) throws InterruptedException {
        if (scheduled.putIfAbsent(entry.url(), entry) != null) return;
        while (!stopping.get() && fatal.get() == null) if (queue.offer(entry, 100, TimeUnit.MILLISECONDS)) return;
    }
    private void worker(CsvState state, Storage storage, HttpFetcher http) {
        try {
            while (!discoveryFinished || !queue.isEmpty()) {
                if (fatal.get() != null) return;
                Entry entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry == null) continue;
                try {
                    Entry result = storage.download(entry, state, http);
                    if (result.status().equals("SKIPPED_DUPLICATE"))
                        log.info("Skipped duplicate {} {} (already saved at {})", result.date(), result.image(), result.localPath());
                    else log.info("Downloaded {} {}", entry.date(), entry.image());
                } catch (HttpFetcher.FetchFailure e) {
                    state.finish(entry.outcome("FAILED", "DOWNLOAD", e.attempts, e.getMessage()));
                    log.warn("Download failed {}: {}", entry.url(), e.getMessage());
                } catch (Storage.Failure e) { throw e;
                } catch (IOException e) { throw new Storage.Failure("State/storage write failed", e); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt();
        } catch (Throwable e) {
            fatal.compareAndSet(null, e); stopping.set(true);
            Thread thread = crawler; if (thread != null) thread.interrupt();
        }
    }
    @Override public void close() {
        Thread thread = crawler;
        if (thread == null || thread == Thread.currentThread()) return;
        stopping.set(true); thread.interrupt();
        try { finished.await(config.shutdownGracePeriod().toMillis() + 6000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
