package io.github.jozephzemambo.jobradar.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jozephzemambo.jobradar.source.BoardNotFoundException;
import io.github.jozephzemambo.jobradar.source.UpstreamException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private static final URI URI_ = URI.create("https://api.lever.co/v0/postings/x");

    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryPolicy policy = new RetryPolicy(4, Duration.ofMillis(500), Duration.ofSeconds(8),
            sleeps::add, new SplittableRandom(42));

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void retriesRetryableFailuresUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();

        String result = policy.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new UpstreamException(URI_, 503, null);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
        assertThat(sleeps.get(0)).isBetween(Duration.ZERO, Duration.ofMillis(500));
        assertThat(sleeps.get(1)).isBetween(Duration.ZERO, Duration.ofMillis(1000));
        assertThat(policy.retryCount()).isEqualTo(2);
    }

    @Test
    void nonRetryableFailureIsAttemptedOnce() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            throw new BoardNotFoundException(URI_);
        })).isInstanceOf(BoardNotFoundException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void givesUpAfterMaxAttemptsWithLastError() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> policy.execute(() -> {
            throw new UpstreamException(URI_, 500 + calls.incrementAndGet(), null);
        })).isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.statusCode()).isEqualTo(504));

        assertThat(calls).hasValue(4);
        assertThat(sleeps).hasSize(3);
    }

    @Test
    void honorsRetryAfterExactly() {
        AtomicInteger calls = new AtomicInteger();

        policy.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new UpstreamException(URI_, 429, Duration.ofSeconds(3));
            }
            return "ok";
        });

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void refusesToWaitForAbsurdRetryAfter() {
        assertThatThrownBy(() -> policy.execute(() -> {
            throw new UpstreamException(URI_, 429, Duration.ofMinutes(10));
        })).isInstanceOf(UpstreamException.class);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void stopsRetryingWhenInterrupted() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new UpstreamException(URI_, new InterruptedException());
        })).isInstanceOf(UpstreamException.class);

        assertThat(calls).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void interruptedSleepRethrowsAndKeepsFlag() {
        RetryPolicy interrupting = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(10),
                d -> { throw new InterruptedException(); }, new SplittableRandom(1));

        assertThatThrownBy(() -> interrupting.execute(() -> {
            throw new UpstreamException(URI_, 503, null);
        })).isInstanceOf(UpstreamException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @ParameterizedTest(name = "attempt {0} -> ceiling {1} ms")
    @CsvSource({"1, 500", "2, 1000", "3, 2000", "4, 4000", "5, 8000", "6, 8000", "40, 8000"})
    void backoffCeilingDoublesThenCaps(int attempt, long expectedMillis) {
        assertThat(policy.backoffCeiling(attempt)).isEqualTo(Duration.ofMillis(expectedMillis));
    }

    @Test
    void jitterStaysWithinCeiling() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(policy.jitteredBackoff(3)).isBetween(Duration.ZERO, Duration.ofMillis(2000));
        }
        assertThat(RetryPolicy.noRetry().jitteredBackoff(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    void rejectsZeroAttempts() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
