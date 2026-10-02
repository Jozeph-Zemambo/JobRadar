package io.github.jozephzemambo.jobradar.http;

import io.github.jozephzemambo.jobradar.source.SourceException;
import io.github.jozephzemambo.jobradar.source.UpstreamException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Retries retryable {@link SourceException}s with capped exponential backoff and full jitter.
 *
 * <p>Full jitter (sleep a uniform random time in {@code [0, min(cap, base * 2^(attempt-1))]}) spreads retries out
 * so that many clients failing at the same moment don't all retry at the same moment. A server-sent
 * {@code Retry-After} overrides the computed delay; if it asks for more than {@link #MAX_RETRY_AFTER} we give up
 * rather than stall the whole ingest.
 */
public class RetryPolicy {

    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private final AtomicLong retries = new AtomicLong();

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {
        this(maxAttempts, initialBackoff, maxBackoff, Sleeper.SYSTEM, RandomGenerator.getDefault());
    }

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Sleeper sleeper,
            RandomGenerator random) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
        this.random = random;
    }

    /** A policy that makes exactly one attempt. */
    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO);
    }

    public <T> T execute(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (SourceException e) {
                // An interrupted thread is being asked to stop; retrying would ignore that request.
                if (!e.retryable() || attempt >= maxAttempts || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                Optional<Duration> retryAfter = e instanceof UpstreamException upstream
                        ? upstream.retryAfter()
                        : Optional.empty();
                if (retryAfter.isPresent() && retryAfter.get().compareTo(MAX_RETRY_AFTER) > 0) {
                    throw e;
                }
                Duration delay = retryAfter.isPresent() ? retryAfter.get() : jitteredBackoff(attempt);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                retries.incrementAndGet();
            }
        }
    }

    /** Delay before retry number {@code attempt} (1-based): uniform in [0, min(max, initial * 2^(attempt-1))]. */
    Duration jitteredBackoff(int attempt) {
        long ceiling = backoffCeiling(attempt).toMillis();
        return ceiling == 0 ? Duration.ZERO : Duration.ofMillis(random.nextLong(ceiling + 1));
    }

    Duration backoffCeiling(int attempt) {
        // Shift capped at 30 so the multiplication can't overflow for silly attempt counts.
        long exponential = initialBackoff.toMillis() << Math.min(attempt - 1, 30);
        return Duration.ofMillis(Math.min(exponential, maxBackoff.toMillis()));
    }

    /** Total retries performed by this policy since startup (exposed as a metric). */
    public long retryCount() {
        return retries.get();
    }
}
