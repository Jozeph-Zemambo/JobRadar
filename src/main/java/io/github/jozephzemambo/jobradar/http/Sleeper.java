package io.github.jozephzemambo.jobradar.http;

import java.time.Duration;

/**
 * Seam over {@link Thread#sleep} so rate-limit and backoff logic can be tested without real waiting.
 * On a virtual thread, {@code Thread.sleep} parks the virtual thread and releases its carrier.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration);

    void sleep(Duration duration) throws InterruptedException;
}
