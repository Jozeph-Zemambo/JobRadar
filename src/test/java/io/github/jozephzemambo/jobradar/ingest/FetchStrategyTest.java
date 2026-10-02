package io.github.jozephzemambo.jobradar.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Asserts on the <em>maximum observed parallelism</em>, not on timings, so these tests don't flake on a busy CI
 * machine.
 */
class FetchStrategyTest {

    private static final List<Company> TWENTY = IntStream.range(0, 20)
            .mapToObj(i -> new Company("C" + i, Ats.GREENHOUSE, "c" + i))
            .toList();

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** Simulates a blocking HTTP call and records how many run at once. */
    private final Function<Company, FetchOutcome> slowTask = company -> {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
        return new FetchOutcome.Success(company, List.of(), Duration.ZERO);
    };

    @Test
    void sequentialRunsOneAtATime() {
        List<FetchOutcome> outcomes = new SequentialFetchStrategy().fetchAll(TWENTY, slowTask);

        assertThat(maxInFlight).hasValue(1);
        assertThat(outcomes).extracting(FetchOutcome::company).containsExactlyElementsOf(TWENTY);
    }

    @Test
    void platformPoolIsCappedAtPoolSize() {
        List<FetchOutcome> outcomes = new PlatformPoolFetchStrategy(4).fetchAll(TWENTY, slowTask);

        assertThat(maxInFlight.get()).isBetween(2, 4);
        assertThat(outcomes).extracting(FetchOutcome::company).containsExactlyElementsOf(TWENTY);
    }

    @Test
    void virtualThreadsRunEveryBoardAtOnce() {
        List<FetchOutcome> outcomes = new VirtualThreadFetchStrategy().fetchAll(TWENTY, slowTask);

        assertThat(maxInFlight.get()).isGreaterThan(4);
        assertThat(outcomes).extracting(FetchOutcome::company).containsExactlyElementsOf(TWENTY);
    }

    @Test
    void virtualThreadTasksReallyRunOnVirtualThreads() {
        AtomicInteger virtualCount = new AtomicInteger();
        new VirtualThreadFetchStrategy().fetchAll(TWENTY, company -> {
            if (Thread.currentThread().isVirtual()) {
                virtualCount.incrementAndGet();
            }
            return new FetchOutcome.Success(company, List.of(), Duration.ZERO);
        });
        assertThat(virtualCount).hasValue(20);
    }

    @Test
    void taskThatBreaksContractFailsLoudly() {
        assertThatThrownBy(() -> new VirtualThreadFetchStrategy().fetchAll(TWENTY.subList(0, 1), c -> {
            throw new IllegalStateException("bug");
        })).isInstanceOf(IllegalStateException.class).hasRootCauseMessage("bug");
    }

    @Test
    void poolSizeMustBePositive() {
        assertThatThrownBy(() -> new PlatformPoolFetchStrategy(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
