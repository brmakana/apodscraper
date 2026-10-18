package com.hexghost.apod;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/** A single scheduler is shared by every request, including redirects and retries. */
final class RequestScheduler {
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }
    private final long interval;
    private final int limit;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final ArrayDeque<Long> starts = new ArrayDeque<>();
    private long next = Long.MIN_VALUE;
    private long cooldown = Long.MIN_VALUE;

    RequestScheduler(Duration interval, int limit) {
        this(interval, limit, System::nanoTime, nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
    }
    RequestScheduler(Duration interval, int limit, LongSupplier clock, Sleeper sleeper) {
        this.interval = interval.toNanos(); this.limit = limit; this.clock = clock; this.sleeper = sleeper;
    }
    void acquire() throws InterruptedException {
        while (true) {
            long delay;
            synchronized (this) {
                long now = clock.getAsLong();
                while (!starts.isEmpty() && now - starts.peekFirst() >= 1_000_000_000L) starts.removeFirst();
                long due = Math.max(next, cooldown);
                if (starts.size() >= limit) due = Math.max(due, starts.peekFirst() + 1_000_000_000L);
                delay = due <= now ? 0 : due - now;
                if (delay == 0) { starts.addLast(now); next = now + interval; return; }
            }
            sleeper.sleep(delay);
        }
    }
    synchronized void pause(Duration duration) {
        cooldown = Math.max(cooldown, clock.getAsLong() + duration.toNanos());
    }
}
