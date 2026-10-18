# APOD Scraper Implementation Plan

Status: approved by the user; implementation started. This plan implements the requirements in `PROJECT.md`.

## Intended behavior

Build a Gradle-based Java Spring Boot command-line application that walks NASA APOD pages backward through their previous-day links, discovers full-resolution images, and downloads them through a bounded queue with a configurable number of workers. Persist progress in CSV files so completed work is retained and unfinished work can resume after shutdown or a crash.

## Proposed decisions

- Use Java 21, a compatible supported Spring Boot release, and the Gradle wrapper. Select and pin compatible dependency versions during implementation.
- Run as a batch application without an HTTP server. Use Spring configuration and lifecycle management, jsoup for HTML parsing, Java HTTP client for requests and streaming downloads, and Apache Commons CSV for CSV handling.
- Use one sequential page crawler and a bounded in-process work queue consumed by a fixed worker pool. This provides the requested queue-driven producer/consumer behavior without an external broker. Each image job goes to one worker.
- Default to `downloadedImages/` for images and `data/` for CSV state. Configure the image destination with `apod.download-directory` in `application.yml`; accept absolute paths to mounted drives as well as relative paths resolved against the process working directory. Keep the CSV state directory independently configurable and exclude generated data from Git.
- Default to two download workers, a queue capacity of sixteen, a 100 ms minimum interval between outgoing requests, a global ceiling of twenty requests per second, bounded network timeouts, and three total attempts for transient request failures. Configure these settings in `application.yml`. Both pacing controls apply, so the default interval permits at most ten request starts per second; twenty per second is a configurable ceiling, not a guarantee that NASA will accept that traffic.

## 1. Project setup and configuration

Create the Gradle application, wrapper, package structure, validated configuration, and test setup. Expose settings in `application.yml` for the initial APOD URL, optional explicit starting article URL, output/state directories, worker count, queue capacity, minimum request interval, global request rate limit, request timeouts, retry policy, and shutdown grace period. Include an optional page limit for controlled runs.

Proposed configuration keys and defaults:

```yaml
apod:
  download-directory: ./downloadedImages
  request-interval: 100ms
  rate-limit-per-second: 20
  download-workers: 2
  queue-size: 16
  retry-failed: false
```

For a mounted drive, set `apod.download-directory` to an absolute path such as `/Volumes/ArchiveDrive/APOD/downloadedImages`. Validate the destination at startup and report missing/unwritable mounted destinations before crawling. Require the configured destination's parent directory to exist before creating the final directory, so a missing mount does not silently create a replacement directory on the local disk. Store image paths relative to the configured download directory in CSV records so relocating the collection does not invalidate those records. Create temporary image files on that destination filesystem to support atomic final moves. If the drive becomes unavailable during a run, stop new discovery, preserve unfinished jobs, and report a storage error for recovery after remounting.

Use one shared, thread-safe request scheduler for HTML fetches and image downloads across all workers. Apply both a minimum interval between request starts and a rolling one-second rate ceiling, without accumulating burst credits. Retries and redirect hops must also acquire permission; handle redirects explicitly so they cannot bypass the limiter. Waiting must be interruptible for shutdown. A `429` response pauses all outgoing requests for `Retry-After`, or the configured backoff when absent; a `503` with `Retry-After` does the same. This request limit controls request starts, not bytes transferred or the number of ongoing downloads.

Use these responsibilities:

| Component | Responsibility |
| --- | --- |
| Startup/resume coordinator | Load state, recover unfinished jobs, select the starting page, and manage completion |
| Page fetcher/parser | Fetch HTML and extract the APOD date, previous-page URL, media type, and full-resolution image URL |
| Crawl coordinator | Walk pages sequentially and publish durable download jobs |
| Download workers | Stream images to disk and report success or failure |
| Shared request scheduler | Enforce request spacing, the global rate ceiling, and server-directed cooldowns |
| CSV state repository | Serialize state changes, deduplicate records, and persist progress safely |
| Shutdown coordinator | Stop discovery, drain workers within the grace period, and preserve remaining jobs |

## 2. Page discovery and parsing

1. On an empty state directory, start from the configured article URL if supplied; otherwise resolve the current article from `https://science.nasa.gov/apod/`. Handle the case where the landing page itself contains the current APOD content.
2. Process the selected starting day's content before following its previous-day link, so the initial image is not accidentally omitted.
3. Extract the previous article from `a.smd-next-prev__prev`. Resolve relative URLs against the fetched page's final URL after redirects.
4. Find the main image under `div.media-detail-hero__media`, and use its enclosing anchor's `href` as the full-resolution download URL. Do not substitute a thumbnail when the full-resolution link cannot be identified.
5. Extract the APOD day from article-specific date/title information, with a tested APOD URL-date fallback. Do not confuse article publication/update timestamps with the APOD day. Store dates in ISO format.
6. Recognize explicit video/embed markup in the main media area and record the day as skipped. Missing image markup alone is a parsing failure, not proof of a video.
7. Follow only the discovered previous-page links; do not construct article slugs from dates. Detect repeated URLs, non-backward dates, and malformed navigation. Stop at a verified archive boundary; report unexpected missing navigation as a resumable error.

Validate selectors and bootstrap behavior against representative live NASA pages during implementation, then capture small HTML fixtures for deterministic tests. The linked landing page and example article were accessible during planning, but raw DOM selectors and historical template variations still need verification. Reference pages: [APOD landing page](https://science.nasa.gov/apod/) and [June 24, 2019 example](https://science.nasa.gov/image-article/apod-2019-june-24-anticrepuscular-rays-converge-opposite-the-sun/).

## 3. CSV persistence and reliable resume

Use a serialized state writer and two CSV files:

- `visited.csv`: `date,page_url,status,image_url,local_path,previous_page_url,failure_stage,attempts,last_attempt_at,last_error`, where status is `DOWNLOADED`, `SKIPPED_VIDEO`, `SKIPPED_DUPLICATE`, or `FAILED`. A failed record is an attempted page, not a successful download. Write download completion only after the final image has been successfully saved. Allow an empty date when parsing/fetching fails before the APOD date can be established; retain the known page URL as its identity.
- `work.csv`: durable discovery and download state, including `date,page_url,previous_page_url,image_url,status,attempts,last_error`. Include a durable crawl-cursor record so discovery can resume even when downloads finish out of order. Specify record types and headers in the README.

Persistence rules:

1. Persist a discovered image job before advancing the crawl cursor or placing the job on the in-memory queue. Persist video skips before advancing as well.
2. Persist changes using temporary files and atomic replacement where supported. Use a single-process file lock to prevent concurrent runs from corrupting shared state.
3. Treat each file update as independent; order writes and reconcile on startup rather than assuming a transaction across both CSV files.
4. On download success, save the final file, persist the visited record, then retire the work record. A crash between steps must produce recoverable duplicate work rather than lost work.
5. At startup, reconcile visited entries with pending/in-progress jobs and files, reset interrupted jobs to pending, and retry outstanding work before continuing discovery. Exclude persisted `FAILED` entries from automatic replay by default, including when a stale work record remains after a crash. With `apod.retry-failed: true`, explicitly retry each failed entry once through the configured bounded retry policy per run. Replace its record on success or update its error on another failure.
6. Resume traversal from the persisted cursor, not the most recently completed worker. When only completed history is available, use the oldest processed APOD day as the backward traversal anchor and rediscover its previous link.
7. Reject malformed state with actionable diagnostics. Keep enough metadata to recover an image saved immediately before a state-write interruption; do not assume an unrelated existing file is a completed download.

Video days count as processed, with an explicit skip status, so restarts do not repeatedly revisit them. Starting from today's page to collect newly published days after a historical crawl is outside the initial scope; an explicit starting URL can begin another traversal while retaining deduplication.

Failure progression: if media/date extraction fails but a valid previous-page link can still be extracted independently, persist `FAILED` and continue backward. Download failures likewise do not block discovery. If fetching or parsing prevents discovering the previous link, persist the failure and blocked cursor, drain pending downloads, and exit with a nonzero result. A normal restart reports that blocked cursor without requesting the failed page again. Recovery requires enabling `retry-failed` or supplying another starting article URL; an explicit starting URL takes precedence over a blocked cursor but does not itself retry known failed pages. Skipping the failing page cannot reveal an unknown previous URL, so do not invent a URL or silently claim traversal is complete.

## 4. Image downloading and failure handling

- Stream HTTP bodies to uniquely named temporary files in the destination filesystem, keeping memory usage bounded. Validate HTTP success, image content type, nonempty content, and expected response length when available before moving to the final filename.
- Preserve the source URL's filename, excluding query parameters and rejecting unsafe filesystem names. Per the user's duplicate-handling amendment, if the destination filename already exists, record `SKIPPED_DUPLICATE` and skip downloading/saving another copy. The first saved file wins even if the remote URL differs; do not create date subdirectories. Retain existing date folders and recognize saved URLs through verified metadata.
- Deduplicate queued and completed jobs by canonical page URL and APOD date. Record reuse of a verified saved image URL as `SKIPPED_DUPLICATE`. Treat existing filename collisions as duplicate skips; coordinate workers by destination filename.
- Retry transient connection failures, rate limits, and server failures with bounded exponential backoff through the shared request scheduler. Permanent HTTP failures and deterministic parser errors become `FAILED` without repeatedly retrying the same response during the run.
- When a page or download becomes `FAILED`, persist its visited record before retiring or marking its work record failed, and allow other discovered downloads to finish. Leave it excluded from automatic retries until `retry-failed` is enabled. If fetching/parsing a page prevents finding the previous link, stop discovery at that page and retain its blocked cursor.
- Propagate persistence errors to the coordinator and stop new discovery. Exit with a nonzero code when unresolved failures remain. Log useful page/date context, retry counts, skips, and final totals.

## 5. Graceful shutdown

Handle Ctrl+C/SIGTERM through Spring's lifecycle hooks. Stop publishing new work, allow active and queued downloads to finish within a configurable grace period, and then cancel remaining transfers. Leave unfinished work persisted for the next run and remove or safely replace abandoned temporary downloads during recovery. Normal end-of-archive and page-limit completion should also drain workers before exiting.

## 6. Verification

Use JUnit and a local HTTP stub server; routine tests must not depend on NASA availability.

- Parser fixtures: full-resolution anchor selection, relative URLs, extra CSS classes, video pages, missing/changed markup, date extraction, navigation cycles, and archive termination.
- Download tests: successful streamed image, redirect, truncated body, invalid content type, timeout, retry exhaustion, filename collision, and safe handling of partial files.
- State/resume tests: CSV quoting and round trips, duplicate records, malformed CSV, interrupted writes, video skip persistence, out-of-order worker completion, crash windows between file/state updates, and interrupted-job replay.
- Failure tests: failed entries are not requested again on normal restart, explicit retry succeeds or remains bounded, missing dates remain trackable by URL, media failure with valid navigation continues, and unknown previous links leave a blocked cursor without a retry loop.
- Pipeline integration tests: a short linked archive with images and video, bounded concurrency, backpressure, failed download recovery with `retry-failed`, persistence failure, and shutdown with queued/in-flight work.
- Request scheduling tests using a controllable clock: shared limits across crawler/workers/retries/redirects, minimum request spacing, rolling-window ceiling, server-directed cooldown, configuration validation, and cancellation while waiting.
- Storage configuration tests: relative and absolute download directories, missing/unwritable destination parents, destination loss during a run, and resume after relocating the image collection while retaining CSV state.
- Run the Gradle test suite and executable build. Perform an explicitly bounded live smoke run against a few NASA pages to verify actual selectors and media selection, documenting results and any network limitations.

Acceptance: the sample article yields its linked full-resolution image; traversal follows previous-day links; videos are recorded as skipped; concurrency respects configuration; and restarting after interruption retrieves all unfinished images without treating failures as completed or overwriting distinct files.

## 7. Documentation and delivery

Write a README covering prerequisites, build/run commands, `application.yml` configuration examples, the interaction between request spacing and the global rate ceiling, CSV schemas, explicit failed-page retry and blocked-cursor recovery, resume behavior, shutdown, collision handling, and recovery from malformed state. Include the tested live-page assumptions and known template limitations. Deliver the application, Gradle wrapper, offline tests/fixtures, and example configuration.

Implementation order: scaffold and configuration → parser and fixtures → CSV state/recovery → downloader and workers → pipeline and shutdown → integration tests, bounded smoke run, and README.

## Approval scope

The user approved implementation of this plan in the repository. Defaults include an in-process queue, CSV-based durable work tracking alongside visited outcomes, shared request pacing and a twenty-per-second ceiling configured in `application.yml`, explicit opt-in retries for `FAILED` entries, processing the starting day itself, recording video skips, and the subsequent user amendment to skip duplicate filenames/saved URLs with `SKIPPED_DUPLICATE` instead of creating date subdirectories.
