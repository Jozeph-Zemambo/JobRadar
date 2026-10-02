package io.github.jozephzemambo.jobradar.dedup;

import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Jaccard similarity of normalized title tokens: |A ∩ B| / |A ∪ B|.
 *
 * <p>Chosen over edit distance because job titles reorder freely ("Engineer, Backend" vs "Backend Engineer") but a
 * single differing token usually changes the job ("Senior" vs "Staff", "Android" vs "iOS"). On a four-word title
 * one differing token scores 3/5 = 0.6, well under the default 0.8 threshold.
 */
@Component
public class TitleTokenJaccard implements StringSimilarity {

    @Override
    public double similarity(String a, String b) {
        Set<String> tokensA = TitleNormalizer.tokens(a);
        Set<String> tokensB = TitleNormalizer.tokens(b);
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        int union = tokensA.size() + tokensB.size() - intersection.size();
        return (double) intersection.size() / union;
    }
}
