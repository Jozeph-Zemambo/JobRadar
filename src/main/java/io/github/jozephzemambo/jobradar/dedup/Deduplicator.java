package io.github.jozephzemambo.jobradar.dedup;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.normalize.LocationNormalizer;
import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Finds postings that describe the same opening.
 *
 * <ol>
 *   <li><b>Exact:</b> identical canonical URL.</li>
 *   <li><b>Fuzzy:</b> within one company (blocking keeps this from being all-pairs over every posting), the
 *   Jaccard similarity of the titles' {@linkplain TitleNormalizer#signatureTokens signature tokens} is at least
 *   the threshold (default 1.0: same signature), the level words agree ("Senior" vs none, "II" vs "III"), both
 *   postings have a location and the location sets overlap, and the departments don't conflict. Location is
 *   essential: Palantir lists "Deployment Strategist" in 11 cities, and those are 11 real openings.</li>
 * </ol>
 *
 * <p>Clustering is leader-based rather than transitive: each posting is compared only to the canonical postings
 * of its block, so A~B and B~C can never chain A and C together unless A~C directly. The earliest-stored posting
 * (lowest id, then ATS key) is the canonical one, which keeps the choice stable from one run to the next.
 *
 * <p>Every candidate is {@linkplain Prepared prepared} once (tokens, level words, location keys) before any
 * comparison, so the O(n x leaders) inner loop only does set lookups. JFR showed the earlier version spending
 * 63% of ingest CPU re-tokenizing the same titles with regexes inside that loop.
 */
@Component
public class Deduplicator {

    private static final Comparator<DedupCandidate> CANONICAL_FIRST = Comparator
            .comparing(DedupCandidate::id, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.key().ats())
            .thenComparing(c -> c.key().externalId());

    private final double threshold;

    @Autowired
    public Deduplicator(JobRadarProperties props) {
        this(props.dedup().titleSimilarityThreshold());
    }

    public Deduplicator(double threshold) {
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be in (0, 1]");
        }
        this.threshold = threshold;
    }

    /** A candidate with everything the fuzzy rule compares computed once. */
    record Prepared(DedupCandidate candidate, String company, Set<String> titleTokens, Set<String> levels,
            Set<String> locationKeys, String department) {

        static Prepared of(DedupCandidate c) {
            String department = c.department() == null || c.department().isBlank()
                    ? null
                    : c.department().strip().toLowerCase(Locale.ROOT);
            return new Prepared(c, c.company().strip().toLowerCase(Locale.ROOT), TitleNormalizer.signatureTokens(c.title()),
                    TitleNormalizer.levelTokens(c.title()), LocationNormalizer.keys(c.locations()), department);
        }
    }

    public DedupResult dedupe(List<DedupCandidate> candidates) {
        List<Prepared> sorted = candidates.stream().sorted(CANONICAL_FIRST).map(Prepared::of).toList();
        List<DedupResult.Duplicate> duplicates = new ArrayList<>();

        // Pass 1: exact canonical URL.
        Map<String, Prepared> firstByUrl = new LinkedHashMap<>();
        List<Prepared> survivors = new ArrayList<>();
        for (Prepared p : sorted) {
            Prepared first = firstByUrl.putIfAbsent(p.candidate().canonicalUrl(), p);
            if (first == null) {
                survivors.add(p);
            } else {
                duplicates.add(new DedupResult.Duplicate(p.candidate(), first.candidate(), DedupResult.Reason.EXACT_URL,
                        TitleTokenJaccard.similarity(p.titleTokens(), first.titleTokens())));
            }
        }

        // Pass 2: fuzzy, blocked by company, leader clustering.
        Map<String, List<Prepared>> blocks = survivors.stream()
                .collect(Collectors.groupingBy(Prepared::company, LinkedHashMap::new, Collectors.toList()));
        long pairs = 0;
        for (List<Prepared> block : blocks.values()) {
            List<Prepared> leaders = new ArrayList<>();
            for (Prepared p : block) {
                Prepared match = null;
                double matchSimilarity = 0;
                for (Prepared leader : leaders) {
                    pairs++;
                    double similarity = TitleTokenJaccard.similarity(p.titleTokens(), leader.titleTokens());
                    if (similarity > matchSimilarity && isMatch(p, leader, similarity)) {
                        match = leader;
                        matchSimilarity = similarity;
                    }
                }
                if (match == null) {
                    leaders.add(p);
                } else {
                    duplicates.add(new DedupResult.Duplicate(p.candidate(), match.candidate(), DedupResult.Reason.FUZZY,
                            matchSimilarity));
                }
            }
        }
        return new DedupResult(candidates.size(), rootCanonicals(duplicates), pairs);
    }

    /**
     * Points every duplicate at the root of its chain. A posting can be an exact-URL duplicate of a posting that
     * the fuzzy pass then links to another one (3 -> 2 -> 1); stored as-is, posting 1's detail view would miss 3.
     */
    private static List<DedupResult.Duplicate> rootCanonicals(List<DedupResult.Duplicate> duplicates) {
        Map<DedupCandidate, DedupCandidate> parent = new HashMap<>();
        for (DedupResult.Duplicate d : duplicates) {
            parent.put(d.duplicate(), d.canonical());
        }
        List<DedupResult.Duplicate> resolved = new ArrayList<>(duplicates.size());
        for (DedupResult.Duplicate d : duplicates) {
            DedupCandidate root = d.canonical();
            while (parent.containsKey(root)) {
                root = parent.get(root);
            }
            resolved.add(root == d.canonical() ? d
                    : new DedupResult.Duplicate(d.duplicate(), root, d.reason(), d.titleSimilarity()));
        }
        return List.copyOf(resolved);
    }

    /** The fuzzy rule for one pair, exposed so the evaluation harness scores exactly what production does. */
    public boolean isFuzzyMatch(DedupCandidate a, DedupCandidate b) {
        Prepared pa = Prepared.of(a);
        Prepared pb = Prepared.of(b);
        return pa.company().equals(pb.company())
                && isMatch(pa, pb, TitleTokenJaccard.similarity(pa.titleTokens(), pb.titleTokens()));
    }

    private boolean isMatch(Prepared a, Prepared b, double similarity) {
        return similarity >= threshold
                && a.levels().equals(b.levels())
                && !Collections.disjoint(a.locationKeys(), b.locationKeys())
                && (a.department() == null || b.department() == null || Objects.equals(a.department(), b.department()));
    }

    public double threshold() {
        return threshold;
    }
}
