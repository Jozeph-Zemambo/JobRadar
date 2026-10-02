package io.github.jozephzemambo.jobradar.bench;

import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import io.github.jozephzemambo.jobradar.dedup.eval.DedupEvaluator;
import io.github.jozephzemambo.jobradar.dedup.eval.PairCsv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Prints the dedup evaluation for one labeled file:
 * {@code ./mvnw test -Dtest=DedupEvaluation -Dbench=true -Dbench.labels=bench/dedup/labeled-pairs.csv}.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class DedupEvaluation {

    @Test
    void evaluate() throws Exception {
        Path file = Path.of(System.getProperty("bench.labels", "bench/dedup/labeled-pairs.csv"));
        List<PairCsv.Row> rows = PairCsv.read(Files.readString(file));
        StringBuilder out = new StringBuilder("Dedup evaluation of " + file + " (" + rows.size() + " pairs)\n\n");

        DedupEvaluator.PrecisionEstimate p = DedupEvaluator.precisionOfPredictedStratum(rows);
        out.append(String.format(Locale.ROOT,
                "Rule that drew the sample, on its 'predicted' stratum: %d/%d correct = %.1f%% (Wilson 95%%: %.1f%%-%.1f%%)%n%n",
                p.correct(), p.labeled(), 100 * p.precision(), 100 * p.low(), 100 * p.high()));

        out.append("Current rule, weighted over the candidate space (in-sample for any threshold chosen from it):\n");
        out.append("| threshold | precision | recall |\n|---|---|---|\n");
        for (DedupEvaluator.SweepPoint point : DedupEvaluator.sweep(rows, new double[] {0.6, 0.7, 0.8, 0.85, 0.9, 1.0})) {
            out.append(String.format(Locale.ROOT, "| %.2f | %.1f%% | %.1f%% |%n", point.threshold(),
                    100 * point.precision(), 100 * point.recall()));
        }

        out.append("\nPairs the current rule (threshold 0.85) gets wrong:\n");
        Deduplicator rule = new Deduplicator(0.85);
        for (PairCsv.Row row : rows) {
            boolean predicted = DedupEvaluator.predicts(rule, row);
            if (row.duplicate() != null && predicted != row.duplicate()) {
                out.append(String.format(Locale.ROOT, "  %s %s: '%s' @ %s  vs  '%s' @ %s  (label %s)%n",
                        row.pairId(), predicted ? "FP" : "FN", row.a().title(), row.a().locations(), row.b().title(),
                        row.b().locations(), row.duplicate() ? "dup" : "distinct"));
            }
        }
        System.out.println(out);
    }
}
