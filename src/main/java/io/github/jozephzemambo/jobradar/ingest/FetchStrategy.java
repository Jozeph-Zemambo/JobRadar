package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Company;
import java.util.List;
import java.util.function.Function;

/**
 * Decides <em>how</em> a set of boards is fetched (sequentially, on a thread pool, on virtual threads), separately
 * from <em>what</em> fetching one board means. The task never throws: failures come back as
 * {@link FetchOutcome.Failure}.
 */
public interface FetchStrategy {

    FetchMode mode();

    /** Runs {@code task} for every company and returns the outcomes in the same order as {@code companies}. */
    List<FetchOutcome> fetchAll(List<Company> companies, Function<Company, FetchOutcome> task);
}
