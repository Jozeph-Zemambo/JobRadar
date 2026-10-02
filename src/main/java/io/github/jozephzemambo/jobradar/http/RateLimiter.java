package io.github.jozephzemambo.jobradar.http;

/** Blocks the caller until it may send one request to {@code key} (a host). */
public interface RateLimiter {

    /** A limiter that never waits; used in unit tests that aren't about rate limiting. */
    RateLimiter UNLIMITED = key -> { };

    void acquire(String key) throws InterruptedException;
}
