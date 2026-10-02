package io.github.jozephzemambo.jobradar.dedup;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.normalize.LocationNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Finds postings that describe the same opening.
 *
 * <ol>
 *   <li><b>Exact:</b> identical canonical URL.</li>
 *   <li><b>Fuzzy:</b> within one company (blocking keeps this from being all-pairs over every posting), the title
 *   similarity is at least the threshold, the location sets overlap, and the departments don't conflict.
 *   Location is essential: Palantir lists "Deployment Strategist" in 11 cities, and those are 11 real openings.</li>
 * </ol>
 *
 * <p>Clustering is leader-based rather than transitive: each posting is compared only to the canonical postings
 * of its block, so A~B and B~C can never chain A and C together unless A~C directly. The earliest-stored posting
 * (lowest id, then ATS key) is the canonical one, which keeps the choice stable from one run to the next.
 */
@Component
public class Deduplicator {

    private static final Comparator<DedupCandidate> CANONICAL_FIRST = Comparator
            .comparing(DedupCandidate::id, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.key().ats())
            .thenComparing(c -> c.key().externalId());

    private final StringSimilarity titleSimilarity;
    private final double threshold;

    @Autowired
    public Deduplicator(StringSimilarity titleSimilarity, JobRadarProperties props) {
        this(titleSimilarity, props.dedup().titleSimilarityThreshold());
    }

    public Deduplicator(StringSimilarity titleSimilarity, double threshold) {
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be in (0, 1]");
        }
        this.titleSimilarity = titleSimilarity;
        this.threshold = threshold;
    }

    public DedupResult dedupe(List<DedupCandidate> candidates) {
        List<DedupCandidate> sorted = candidates.stream().sorted(CANONICAL_FIRST).toList();
        List<DedupResult.Duplicate> duplicates = new ArrayList<>();

        // Pass 1: exact canonical URL.
        Map<String, DedupCandidate> firstByUrl = new LinkedHashMap<>();
        List<DedupCandidate> survivors = new ArrayList<>();
        for (DedupCandidate candidate : sorted) {
            DedupCandidate first = firstByUrl.putIfAbsent(candidate.canonicalUrl(), candidate);
            if (first == null) {
                survivors.add(candidate);
            } else {
                duplicates.add(new DedupResult.Duplicate(candidate, first, DedupResult.Reason.EXACT_URL,
                        titleSimilarity.similarity(candidate.title(), first.title())));
            }
        }

        // Pass 2: fuzzy, blocked by company, leader clustering.
        Map<String, List<DedupCandidate>> blocks = survivors.stream().collect(Collectors.groupingBy(
                c -> c.company().strip().toLowerCase(Locale.ROOT), LinkedHashMap::new, Collectors.toList()));
        long pairs = 0;
        for (List<DedupCandidate> block : blocks.values()) {
            List<DedupCandidate> leaders = new ArrayList<>();
            for (DedupCandidate candidate : block) {
                DedupCandidate match = null;
                double matchSimilarity = 0;
                for (DedupCandidate leader : leaders) {
                    pairs++;
                    double similarity = titleSimilarity.similarity(candidate.title(), leader.title());
                    if (similarity > matchSimilarity && isMatch(candidate, leader, similarity)) {
                        match = leader;
                        matchSimilarity = similarity;
                    }
                }
                if (match == null) {
                    leaders.add(candidate);
                } else {
                    duplicates.add(new DedupResult.Duplicate(candidate, match, DedupResult.Reason.FUZZY,
                            matchSimilarity));
                }
            }
        }
        return new DedupResult(candidates.size(), List.copyOf(duplicates), pairs);
    }

    /** The fuzzy rule for one pair, exposed so the evaluation harness scores exactly what production does. */
    public boolean isFuzzyMatch(DedupCandidate a, DedupCandidate b) {
        if (!a.company().strip().equalsIgnoreCase(b.company().strip())) {
            return false;
        }
        return isMatch(a, b, titleSimilarity.similarity(a.title(), b.title()));
    }

    public double titleSimilarity(DedupCandidate a, DedupCandidate b) {
        return titleSimilarity.similarity(a.title(), b.title());
    }

    private boolean isMatch(DedupCandidate a, DedupCandidate b, double similarity) {
        return similarity >= threshold
                && LocationNormalizer.overlaps(a.locations(), b.locations())
                && departmentsCompatible(a.department(), b.department());
    }

    /** Unknown department is compatible with anything; two known departments must agree. */
    private static boolean departmentsCompatible(String a, String b) {
        if (a == null || a.isBlank() || b == null || b.isBlank()) {
            return true;
        }
        return Objects.equals(a.strip().toLowerCase(Locale.ROOT), b.strip().toLowerCase(Locale.ROOT));
    }

    public double threshold() {
        return threshold;
    }
}
