package com.hexghost.apod;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RequestSchedulerTest {
    @Test void intervalAndRollingCeilingBothApplyWithoutBurstCredits() throws Exception {
        AtomicLong now = new AtomicLong();
        var limiter = new RequestScheduler(Duration.ofMillis(100), 2, now::get, now::addAndGet);
        limiter.acquire(); assertEquals(0, now.get());
        limiter.acquire(); assertEquals(100_000_000, now.get());
        limiter.acquire(); assertEquals(1_000_000_000, now.get());
        limiter.acquire(); assertEquals(1_100_000_000, now.get());
        now.addAndGet(10_000_000_000L);
        limiter.acquire(); long first = now.get(); limiter.acquire(); assertEquals(first + 100_000_000, now.get());
    }
    @Test void cooldownAffectsEveryConsumer() throws Exception {
        AtomicLong now = new AtomicLong();
        var limiter = new RequestScheduler(Duration.ZERO, 20, now::get, now::addAndGet);
        limiter.pause(Duration.ofSeconds(4)); limiter.acquire(); assertEquals(4_000_000_000L, now.get());
    }
    @Test void waitingIsInterruptible() throws Exception {
        AtomicLong now = new AtomicLong();
        var limiter = new RequestScheduler(Duration.ofSeconds(1), 20, now::get, n -> { throw new InterruptedException(); });
        limiter.acquire(); assertThrows(InterruptedException.class, limiter::acquire);
    }
}
