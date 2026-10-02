package io.github.jozephzemambo.jobradar.scoring;

import io.github.jozephzemambo.jobradar.domain.Posting;

/**
 * Ranks a posting against a profile. Implementations must be thread-safe and side-effect free.
 * The active implementation is chosen by the {@code jobradar.scorer} property.
 */
public interface Scorer {

    ScoreBreakdown score(Posting posting, Profile profile);
}
