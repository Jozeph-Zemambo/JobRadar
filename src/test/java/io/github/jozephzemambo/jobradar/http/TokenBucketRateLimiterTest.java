package io.github.jozephzemambo.jobradar.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    /** A clock that only moves when the limiter "sleeps", so the tests are exact and instant. */
    private final AtomicLong nanos = new AtomicLong(0);
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper fakeSleeper = d -> {
        sleeps.add(d);
        nanos.addAndGet(d.toNanos());
    };

    @Test
    void burstGoesImmediatelyThenRequestsAreSpacedByRate() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2.0, 2, nanos::get, fakeSleeper);

        limiter.acquire("api.lever.co");
        limiter.acquire("api.lever.co");
        assertThat(sleeps).isEmpty();

        limiter.acquire("api.lever.co");
        limiter.acquire("api.lever.co");
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @Test
    void idleTimeRefillsButNeverBeyondBurst() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 2, nanos::get, fakeSleeper);
        limiter.acquire("h");
        limiter.acquire("h");

        nanos.addAndGet(Duration.ofSeconds(60).toNanos()); // a long idle period refills at most `burst` tokens

        limiter.acquire("h");
        limiter.acquire("h");
        assertThat(sleeps).isEmpty();
        limiter.acquire("h");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void hostsHaveIndependentBuckets() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 1, nanos::get, fakeSleeper);
        limiter.acquire("boards-api.greenhouse.io");
        limiter.acquire("api.lever.co");
        limiter.acquire("api.ashbyhq.com");
        assertThat(sleeps).isEmpty();
    }

    @Test
    void concurrentWaitersReserveDistinctSlots() throws Exception {
        // Real clock: 10 virtual threads at 20/s with burst 1 cannot finish faster than 9 intervals (450 ms).
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(20.0, 1);
        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    limiter.acquire("host");
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(440);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketRateLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
