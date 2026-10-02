package io.github.jozephzemambo.jobradar.dedup;

import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import java.util.Set;

/**
 * Jaccard similarity of normalized title tokens: |A ∩ B| / |A ∪ B|.
 *
 * <p>Chosen over edit distance because job titles reorder freely ("Engineer, Backend" vs "Backend Engineer") but a
 * single differing token usually changes the job ("Senior" vs "Staff", "Android" vs "iOS"). On a four-word title
 * one differing token scores 3/5 = 0.6, well under the default 0.85 threshold.
 *
 * <p>The set overload exists for speed: callers comparing one title against many should tokenize it once.
 * Re-tokenizing per comparison was 63% of ingest CPU before {@link Deduplicator} started preparing candidates.
 */
public final class TitleTokenJaccard {

    private TitleTokenJaccard() {
    }

    public static double similarity(String a, String b) {
        return similarity(TitleNormalizer.tokens(a), TitleNormalizer.tokens(b));
    }

    public static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        Set<String> smaller = a.size() <= b.size() ? a : b;
        Set<String> larger = smaller == a ? b : a;
        int common = 0;
        for (String token : smaller) {
            if (larger.contains(token)) {
                common++;
            }
        }
        return (double) common / (a.size() + b.size() - common);
    }
}
