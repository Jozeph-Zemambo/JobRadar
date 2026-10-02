package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import java.time.Duration;
import java.util.List;

/**
 * Result of fetching one board. Sealed, so a {@code switch} over it is checked for exhaustiveness by the compiler.
 * Failures are values, not exceptions: one broken board must not abort the other forty-four.
 */
public sealed interface FetchOutcome {

    Company company();

    Duration elapsed();

    record Success(Company company, List<Posting> postings, Duration elapsed) implements FetchOutcome {
        public Success {
            postings = List.copyOf(postings);
        }
    }

    record Failure(Company company, String errorType, String message, Duration elapsed) implements FetchOutcome {
    }
}
