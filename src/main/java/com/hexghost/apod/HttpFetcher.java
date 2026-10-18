package com.hexghost.apod;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;

final class HttpFetcher implements AutoCloseable {
    static final class FetchFailure extends IOException {
        final int attempts;
        FetchFailure(String message, int attempts, Throwable cause) { super(message, cause); this.attempts = attempts; }
    }
    record Result<T>(HttpResponse<T> response, int attempts) {}
    private final ApodProperties config;
    private final RequestScheduler scheduler;
    private final HttpClient client;

    HttpFetcher(ApodProperties config, RequestScheduler scheduler) {
        this.config = config; this.scheduler = scheduler;
        client = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    Result<String> html(URI uri) throws IOException, InterruptedException {
        return fetch(uri, HttpResponse.BodyHandlers.ofString());
    }
    Result<Path> image(URI uri, Path temporary) throws IOException, InterruptedException {
        // The file subscriber streams with bounded buffers, and request timeout covers body completion.
        return fetch(uri, HttpResponse.BodyHandlers.ofFile(temporary,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
    }
    private <T> Result<T> fetch(URI original, HttpResponse.BodyHandler<T> body)
            throws IOException, InterruptedException {
        Throwable last = null;
        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            URI current = Urls.validate(original);
            boolean retry = false;
            try {
                for (int redirects = 0; ; redirects++) {
                    scheduler.acquire();
                    var request = HttpRequest.newBuilder(current).timeout(config.requestTimeout())
                            .header("User-Agent", "ApodScraper/1.0 (personal APOD archive)").GET().build();
                    CompletableFuture<HttpResponse<T>> future = client.sendAsync(request, info -> {
                        if (info.statusCode() >= 200 && info.statusCode() < 300) return body.apply(info);
                        return HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.discarding(), ignored -> null);
                    });
                    HttpResponse<T> response;
                    try { response = future.get(config.requestTimeout().toNanos(), TimeUnit.NANOSECONDS); }
                    catch (TimeoutException e) {
                        future.cancel(true);
                        throw new HttpTimeoutException("Request/body timeout: " + current);
                    } catch (InterruptedException e) { future.cancel(true); throw e;
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof IOException io) throw io;
                        throw new IOException("HTTP request failed", e.getCause());
                    }
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) return new Result<>(response, attempt);
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        if (redirects >= 8) throw new FetchFailure("Too many redirects: " + original, attempt, null);
                        String location = response.headers().firstValue("Location").orElseThrow(
                                () -> new IOException("Redirect has no Location header"));
                        current = Urls.validate(current.resolve(location));
                        continue;
                    }
                    Duration backoff = backoff(attempt);
                    var retryAfter = response.headers().firstValue("Retry-After");
                    if (status == 429 || (status == 503 && retryAfter.isPresent()))
                        scheduler.pause(retryAfter.map(this::retryAfter).orElse(backoff));
                    if (status == 429 || status == 408 || status >= 500) {
                        last = new IOException("HTTP " + status + " for " + current);
                        retry = true;
                        break;
                    }
                    throw new FetchFailure("HTTP " + status + " for " + current, attempt, null);
                }
            } catch (FetchFailure e) { throw e;
            } catch (IOException e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause())
                    if (cause instanceof FileSystemException) throw new Storage.Failure("Cannot write image response", e);
                last = e; retry = true;
            }
            if (retry && attempt < config.maxAttempts()) TimeUnit.NANOSECONDS.sleep(backoff(attempt).toNanos());
        }
        throw new FetchFailure("Request failed after " + config.maxAttempts() + " attempts: " + original
                + " (" + (last == null ? "unknown error" : last.getMessage()) + ")", config.maxAttempts(), last);
    }
    private Duration backoff(int attempt) {
        return config.retryBackoff().multipliedBy(1L << Math.min(attempt - 1, 10));
    }
    private Duration retryAfter(String value) {
        try { return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim()))); }
        catch (RuntimeException ignored) {
            try { return Duration.ofMillis(Math.max(0, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - Instant.now().toEpochMilli())); }
            catch (RuntimeException invalid) { return config.retryBackoff(); }
        }
    }
    @Override public void close() { client.shutdownNow(); }
}
