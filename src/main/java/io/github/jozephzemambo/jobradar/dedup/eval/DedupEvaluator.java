package io.github.jozephzemambo.jobradar.dedup.eval;

import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Scores the dedup rule against labeled pairs.
 *
 * <p>Two kinds of estimate, kept separate on purpose:
 * <ul>
 *   <li><b>Precision at the production threshold</b> from the "predicted" stratum alone. That stratum is a simple
 *   random sample of the rule's links, so the plain proportion and its Wilson 95% interval are valid.</li>
 *   <li><b>Weighted precision and recall</b> at any threshold, using each pair's inverse-sampling weight. These
 *   cover the whole candidate space (same-company pairs with title similarity >= 0.5) and drive the threshold
 *   sweep.</li>
 * </ul>
 */
public final class DedupEvaluator {

    private static final double Z_95 = 1.959964;

    private DedupEvaluator() {
    }

    /**
     * @param truePositives  labeled dup and predicted dup
     * @param falsePositives labeled distinct but predicted dup
     * @param falseNegatives labeled dup but predicted distinct
     * @param trueNegatives  labeled distinct and predicted distinct
     */
    public record Confusion(double truePositives, double falsePositives, double falseNegatives,
            double trueNegatives) {

        public double precision() {
            double predicted = truePositives + falsePositives;
            return predicted == 0 ? Double.NaN : truePositives / predicted;
        }

        public double recall() {
            double actual = truePositives + falseNegatives;
            return actual == 0 ? Double.NaN : truePositives / actual;
        }
    }

    /** Precision of the rule on the uniformly sampled "predicted" stratum, with a Wilson 95% interval. */
    public record PrecisionEstimate(int labeled, int correct, double precision, double low, double high) {
    }

    public record SweepPoint(double threshold, double precision, double recall) {
    }

    /** Whether the rule calls this pair a duplicate: same canonical URL, or the fuzzy rule. */
    public static boolean predicts(Deduplicator dedup, PairCsv.Row row) {
        return Objects.equals(row.a().canonicalUrl(), row.b().canonicalUrl()) || dedup.isFuzzyMatch(row.a(), row.b());
    }

    public static PrecisionEstimate precisionOfPredictedStratum(List<PairCsv.Row> rows) {
        List<PairCsv.Row> predicted = labeled(rows).stream().filter(r -> r.stratum().equals("predicted")).toList();
        int correct = (int) predicted.stream().filter(PairCsv.Row::duplicate).count();
        double[] interval = wilson(correct, predicted.size());
        return new PrecisionEstimate(predicted.size(), correct,
                predicted.isEmpty() ? Double.NaN : correct / (double) predicted.size(), interval[0], interval[1]);
    }

    public static Confusion weighted(List<PairCsv.Row> rows, Deduplicator dedup) {
        double tp = 0;
        double fp = 0;
        double fn = 0;
        double tn = 0;
        for (PairCsv.Row row : labeled(rows)) {
            boolean predicted = predicts(dedup, row);
            double w = row.weight();
            if (row.duplicate() && predicted) {
                tp += w;
            } else if (!row.duplicate() && predicted) {
                fp += w;
            } else if (row.duplicate()) {
                fn += w;
            } else {
                tn += w;
            }
        }
        return new Confusion(tp, fp, fn, tn);
    }

    public static List<SweepPoint> sweep(List<PairCsv.Row> rows, double[] thresholds) {
        List<SweepPoint> points = new ArrayList<>();
        for (double threshold : thresholds) {
            Confusion c = weighted(rows, new Deduplicator(threshold));
            points.add(new SweepPoint(threshold, c.precision(), c.recall()));
        }
        return points;
    }

    /** Wilson score interval: well-behaved for small n and proportions near 0 or 1, unlike p +/- z*sqrt(pq/n). */
    static double[] wilson(int successes, int n) {
        if (n == 0) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double p = successes / (double) n;
        double z2 = Z_95 * Z_95;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / denominator;
        double margin = Z_95 * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new double[] {Math.max(0, centre - margin), Math.min(1, centre + margin)};
    }

    private static List<PairCsv.Row> labeled(List<PairCsv.Row> rows) {
        return rows.stream().filter(r -> r.duplicate() != null).toList();
    }
}
