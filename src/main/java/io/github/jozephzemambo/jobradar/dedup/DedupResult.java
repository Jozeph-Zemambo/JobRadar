package io.github.jozephzemambo.jobradar.dedup;

import java.util.List;

/**
 * Output of one dedup pass. Postings not listed in {@link #duplicates} are canonical.
 *
 * @param candidates      how many postings were considered
 * @param duplicates      every posting judged a duplicate, with the posting it duplicates
 * @param pairsCompared   fuzzy comparisons performed (shows the effect of blocking by company)
 */
public record DedupResult(int candidates, List<Duplicate> duplicates, long pairsCompared) {

    public enum Reason {
        /** Same canonical URL: the same posting reached through two boards or links. */
        EXACT_URL,
        /** Same company, near-identical title, overlapping location and compatible department. */
        FUZZY
    }

    public record Duplicate(DedupCandidate duplicate, DedupCandidate canonical, Reason reason, double titleSimilarity) {
    }

    public long count(Reason reason) {
        return duplicates.stream().filter(d -> d.reason() == reason).count();
    }
}
