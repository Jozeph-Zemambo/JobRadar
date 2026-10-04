package io.github.jozephzemambo.jobradar.dedup.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the dedup rule against the committed, labeled validation sample: a change to normalization or matching
 * that makes the rule worse on these 100 real pairs fails the build. The threshold matches the production default
 * ({@code jobradar.dedup.title-similarity-threshold}).
 */
class DedupRegressionTest {

    private static final double PRODUCTION_THRESHOLD = 1.0;

    @Test
    void ruleIsNoWorseOnTheValidationLabels() throws Exception {
        List<PairCsv.Row> rows = PairCsv.read(Files.readString(Path.of("bench/dedup/validation-labeled.csv")));
        assertThat(rows).hasSize(100).allSatisfy(r -> assertThat(r.duplicate()).isNotNull());

        DedupEvaluator.Confusion confusion = DedupEvaluator.weighted(rows, new Deduplicator(PRODUCTION_THRESHOLD));

        // Values measured when the rule was frozen on 2026-10-02 (see docs/BENCHMARKS.md).
        assertThat(confusion.precision()).isGreaterThanOrEqualTo(0.86 - 1e-9);
        assertThat(confusion.recall()).isGreaterThanOrEqualTo(1.0 - 1e-9);
    }

    @Test
    void ruleIsNoWorseOnTheSecondHeldOutSample() throws Exception {
        List<PairCsv.Row> rows = PairCsv.read(Files.readString(Path.of("bench/dedup/holdout2-labeled.csv")));
        assertThat(rows).hasSize(100).allSatisfy(r -> assertThat(r.duplicate()).isNotNull());

        DedupEvaluator.Confusion confusion = DedupEvaluator.weighted(rows, new Deduplicator(PRODUCTION_THRESHOLD));
        DedupEvaluator.PrecisionEstimate drawn = DedupEvaluator.precisionOfPredictedStratum(rows);

        // Measured 2026-10-04 on a sample drawn after this rule was committed (see docs/BENCHMARKS.md).
        assertThat(drawn.correct()).isEqualTo(47);
        assertThat(confusion.precision()).isGreaterThanOrEqualTo(0.94 - 1e-9);
        assertThat(confusion.recall()).isGreaterThanOrEqualTo(0.794 - 1e-3);
    }

    @Test
    void developmentSampleKeepsItsPreRegisteredResult() throws Exception {
        List<PairCsv.Row> rows = PairCsv.read(Files.readString(Path.of("bench/dedup/labeled-pairs.csv")));

        DedupEvaluator.PrecisionEstimate original = DedupEvaluator.precisionOfPredictedStratum(rows);

        assertThat(original.correct()).isEqualTo(39);
        assertThat(original.labeled()).isEqualTo(50);
    }
}
