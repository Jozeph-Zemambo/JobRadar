package io.github.jozephzemambo.jobradar.dedup.eval;

import io.github.jozephzemambo.jobradar.dedup.DedupCandidate;
import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Draws a stratified sample of posting pairs for labeling, so both precision and recall can be estimated.
 *
 * <ul>
 *   <li><b>predicted</b>: same-company pairs the production rule's pairwise predicate
 *   ({@link Deduplicator#isFuzzyMatch}) calls duplicates, sampled uniformly. Precision is estimated directly from
 *   this stratum.</li>
 *   <li><b>near_*</b>: same-company pairs the predicate rejects but whose titles are at least 50% similar, split
 *   into similarity bands. Labeled duplicates here are misses, which gives recall.</li>
 * </ul>
 *
 * Strata use the pairwise predicate, not the clustering links, so they agree exactly with what
 * {@link DedupEvaluator#predicts} recomputes.
 *
 * Each pair carries a weight = stratum population / stratum sample size, so estimates over the whole candidate
 * space can be computed from the sample. Pairs below 0.5 title similarity are assumed distinct and not sampled.
 */
public final class PairSampler {

    static final double NEAR_MISS_FLOOR = 0.5;
    static final double[] BANDS = {0.5, 0.7, 0.9};

    private PairSampler() {
    }

    /** A sampled pair, before labeling. */
    public record SampledPair(String stratum, double weight, DedupCandidate a, DedupCandidate b,
            double titleSimilarity) {
    }

    public static List<SampledPair> sample(List<DedupCandidate> open, Deduplicator dedup, int predictedCount,
            int nearMissCount, long seed) {
        return sample(open, dedup, predictedCount, nearMissCount, seed, pair -> true);
    }

    /**
     * @param eligible pairs failing this are left out of every stratum's population (used to draw a validation
     *                 sample disjoint from an earlier one)
     */
    public static List<SampledPair> sample(List<DedupCandidate> open, Deduplicator dedup, int predictedCount,
            int nearMissCount, long seed, Predicate<SampledPair> eligible) {
        Random random = new Random(seed);
        List<SampledPair> predicted = new ArrayList<>();
        Map<String, List<SampledPair>> nearByBand = new LinkedHashMap<>();
        for (int i = BANDS.length - 1; i >= 0; i--) {
            nearByBand.put(bandName(i), new ArrayList<>());
        }
        Map<String, List<DedupCandidate>> byCompany = open.stream().collect(Collectors.groupingBy(
                c -> c.company().strip().toLowerCase(Locale.ROOT), LinkedHashMap::new, Collectors.toList()));
        for (List<DedupCandidate> block : byCompany.values()) {
            List<Set<String>> tokens = block.stream().map(c -> TitleNormalizer.tokens(c.title())).toList();
            for (int i = 0; i < block.size(); i++) {
                for (int j = i + 1; j < block.size(); j++) {
                    double sim = jaccard(tokens.get(i), tokens.get(j));
                    if (sim < NEAR_MISS_FLOOR) {
                        continue;
                    }
                    SampledPair pair = new SampledPair(null, 0, block.get(i), block.get(j), sim);
                    if (!eligible.test(pair)) {
                        continue;
                    }
                    if (dedup.isFuzzyMatch(block.get(i), block.get(j))) {
                        predicted.add(pair);
                    } else {
                        nearByBand.get(bandName(band(sim))).add(pair);
                    }
                }
            }
        }

        List<SampledPair> sample = new ArrayList<>(take(predicted, predictedCount, "predicted", random));
        int bands = nearByBand.size();
        int index = 0;
        for (Map.Entry<String, List<SampledPair>> band : nearByBand.entrySet()) {
            // Split the near-miss budget evenly; earlier (higher-similarity) bands get any remainder.
            int quota = nearMissCount / bands + (index++ < nearMissCount % bands ? 1 : 0);
            sample.addAll(take(band.getValue(), quota, band.getKey(), random));
        }
        return sample;
    }

    private static List<SampledPair> take(List<SampledPair> population, int count, String stratum, Random random) {
        List<SampledPair> shuffled = new ArrayList<>(population);
        Collections.shuffle(shuffled, random);
        List<SampledPair> chosen = shuffled.subList(0, Math.min(count, shuffled.size()));
        double weight = chosen.isEmpty() ? 0 : population.size() / (double) chosen.size();
        return chosen.stream()
                .map(p -> new SampledPair(stratum, weight, p.a(), p.b(), p.titleSimilarity()))
                .toList();
    }

    static int band(double similarity) {
        for (int i = BANDS.length - 1; i >= 0; i--) {
            if (similarity >= BANDS[i]) {
                return i;
            }
        }
        throw new IllegalArgumentException("below floor: " + similarity);
    }

    static String bandName(int band) {
        return String.format(Locale.ROOT, "near_%.1f", BANDS[band]);
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        int common = 0;
        for (String token : a) {
            if (b.contains(token)) {
                common++;
            }
        }
        return (double) common / (a.size() + b.size() - common);
    }
}
