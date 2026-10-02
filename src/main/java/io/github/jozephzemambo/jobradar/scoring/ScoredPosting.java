package io.github.jozephzemambo.jobradar.scoring;

import io.github.jozephzemambo.jobradar.domain.Posting;
import java.util.Objects;

/**
 * A posting with its score, as handed to persistence.
 *
 * @param posting the posting
 * @param score   its breakdown, or null if it hasn't been scored
 */
public record ScoredPosting(Posting posting, ScoreBreakdown score) {

    public ScoredPosting {
        Objects.requireNonNull(posting, "posting");
    }
}
