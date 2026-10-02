package io.github.jozephzemambo.jobradar.http;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Per-key token bucket: {@code burst} requests may go immediately, after which requests are spaced at
 * {@code permitsPerSecond}.
 *
 * <p>Implementation notes, because these are the interesting parts:
 * <ul>
 *   <li><b>Reservation, not polling.</b> A caller that finds the bucket empty takes a token anyway (driving the
 *   balance negative) and sleeps for exactly the deficit. Each waiter has a distinct slot, so there is no
 *   thundering herd of threads waking up to fight over one token, and callers are served in lock order.</li>
 *   <li><b>{@link ReentrantLock}, not {@code synchronized}.</b> On JDK 21 a virtual thread blocked inside
 *   {@code synchronized} pins its carrier thread (fixed in JDK 24 by JEP 491). The lock is held only for
 *   arithmetic, and the sleep happens after it is released.</li>
 *   <li><b>{@link ConcurrentHashMap#computeIfAbsent}</b> creates each host's bucket exactly once even when many
 *   threads hit a new host at the same moment.</li>
 * </ul>
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final double permitsPerSecond;
    private final double burst;
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(double permitsPerSecond, int burst) {
        this(permitsPerSecond, burst, System::nanoTime, Sleeper.SYSTEM);
    }

    public TokenBucketRateLimiter(double permitsPerSecond, int burst, LongSupplier nanoClock, Sleeper sleeper) {
        if (!Double.isFinite(permitsPerSecond) || permitsPerSecond <= 0) {
            // NaN fails every comparison, so "<= 0" alone would let NaN through and silently disable throttling.
            throw new IllegalArgumentException("permitsPerSecond must be a positive number");
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be at least 1");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.burst = burst;
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
    }

    @Override
    public void acquire(String key) throws InterruptedException {
        Duration wait = buckets.computeIfAbsent(key, k -> new Bucket(nanoClock.getAsLong())).reserve();
        if (!wait.isZero()) {
            sleeper.sleep(wait);
        }
    }

    private final class Bucket {
        private final ReentrantLock lock = new ReentrantLock();
        private double tokens;
        private long lastRefillNanos;

        Bucket(long now) {
            this.tokens = burst;
            this.lastRefillNanos = now;
        }

        /** Takes one token and returns how long the caller must wait before using it. */
        Duration reserve() {
            lock.lock();
            try {
                long now = nanoClock.getAsLong();
                double refill = (now - lastRefillNanos) * permitsPerSecond / NANOS_PER_SECOND;
                tokens = Math.min(burst, tokens + refill);
                lastRefillNanos = now;
                tokens -= 1;
                if (tokens >= 0) {
                    return Duration.ZERO;
                }
                return Duration.ofNanos((long) Math.ceil(-tokens / permitsPerSecond * NANOS_PER_SECOND));
            } finally {
                lock.unlock();
            }
        }
    }
}
