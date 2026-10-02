package io.github.jozephzemambo.jobradar.dedup.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.jozephzemambo.jobradar.dedup.DedupCandidate;
import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.PostingKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DedupEvalUnitTest {

    private final Deduplicator dedup = new Deduplicator(0.8);

    @Test
    void samplerStratifiesByPredicateAndSimilarityWithInverseWeights() {
        List<DedupCandidate> open = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            open.add(c("Acme", "Data Engineer", "Berlin")); // 6 predicted pairs among these 4
        }
        open.add(c("Acme", "Data Engineer", "Paris"));       // same title, other city: near_0.9 with each Berlin one
        open.add(c("Acme", "Senior Data Engineer", "Berlin")); // level differs: near_0.5 (2/3 = 0.667)
        open.add(c("Other", "Data Engineer", "Berlin"));     // other company: never paired with Acme

        List<PairSampler.SampledPair> sample = PairSampler.sample(open, dedup, 3, 3, 42);

        Map<String, List<PairSampler.SampledPair>> byStratum = sample.stream()
                .collect(Collectors.groupingBy(PairSampler.SampledPair::stratum));
        assertThat(byStratum.get("predicted")).hasSize(3).allSatisfy(p -> assertThat(p.weight()).isEqualTo(2.0));
        assertThat(byStratum.get("near_0.9")).hasSize(1).allSatisfy(p -> assertThat(p.weight()).isEqualTo(4.0));
        assertThat(byStratum.get("near_0.5")).hasSize(1).allSatisfy(p -> assertThat(p.weight()).isEqualTo(5.0));
        assertThat(byStratum).doesNotContainKey("near_0.7");
        assertThat(sample).allSatisfy(p -> assertThat(p.a().company()).isEqualTo(p.b().company()));
        assertThat(PairSampler.sample(open, dedup, 3, 3, 42)).as("seeded").isEqualTo(sample);
    }

    @Test
    void bandBoundaries() {
        assertThat(PairSampler.band(0.5)).isZero();
        assertThat(PairSampler.band(0.7)).isEqualTo(1);
        assertThat(PairSampler.band(1.0)).isEqualTo(2);
        assertThatThrownBy(() -> PairSampler.band(0.4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void csvRoundTripsQuotesCommasAndNewlines() {
        PairCsv.Row row = new PairCsv.Row("p001", "predicted", 2.5, 1.0,
                c("Acme, Inc.", "Engineer \"Platform\"", "New York, NY"), "Line one\nline two",
                c("Acme, Inc.", "Engineer", "NYC"), "", true, "same req");

        List<PairCsv.Row> back = PairCsv.read(PairCsv.write(List.of(row)));

        assertThat(back).singleElement().satisfies(r -> {
            assertThat(r.a().title()).isEqualTo("Engineer \"Platform\"");
            assertThat(r.a().locations()).containsExactly("New York, NY");
            assertThat(r.snippetA()).isEqualTo("Line one\nline two");
            assertThat(r.duplicate()).isTrue();
            assertThat(r.weight()).isEqualTo(2.5);
            assertThat(r.a().key()).isEqualTo(row.a().key());
        });
    }

    @Test
    void csvRejectsBadLabelsAndHeaders() {
        String csv = PairCsv.write(List.of(new PairCsv.Row("p1", "predicted", 1, 1, c("A", "x", "Paris"), "",
                c("A", "x", "Paris"), "", null, null)));
        assertThat(PairCsv.read(csv).getFirst().duplicate()).isNull();
        assertThatThrownBy(() -> PairCsv.read(csv.replace(",,\n", ",maybe,\n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PairCsv.read("a,b\n1,2\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void precisionOfPredictedStratumWithWilsonInterval() {
        List<PairCsv.Row> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row("predicted", 1, "Data Engineer", "Data Engineer", i < 45));
        }
        rows.add(row("near_0.9", 10, "Data Engineer", "Data Engineer II", true));

        DedupEvaluator.PrecisionEstimate estimate = DedupEvaluator.precisionOfPredictedStratum(rows);

        assertThat(estimate.labeled()).isEqualTo(50);
        assertThat(estimate.precision()).isEqualTo(0.9);
        // Wilson 95% for 45/50: [0.786, 0.957]
        assertThat(estimate.low()).isCloseTo(0.786, within(0.001));
        assertThat(estimate.high()).isCloseTo(0.957, within(0.001));
    }

    @Test
    void weightedConfusionAndSweep() {
        List<PairCsv.Row> rows = List.of(
                row("predicted", 2, "Data Engineer", "Data Engineer", true),
                row("predicted", 2, "Data Engineer", "Data Engineer", false),
                row("near_0.7", 10, "Data Platform Engineer", "Data Engineer", true),   // sim 0.667: missed at 0.8
                row("near_0.7", 10, "Data Engineer, Ads", "Data Engineer", false));      // sim 0.667

        DedupEvaluator.Confusion at08 = DedupEvaluator.weighted(rows, dedup);
        assertThat(at08).isEqualTo(new DedupEvaluator.Confusion(2, 2, 10, 10));
        assertThat(at08.precision()).isEqualTo(0.5);
        assertThat(at08.recall()).isCloseTo(2 / 12.0, within(1e-9));

        List<DedupEvaluator.SweepPoint> sweep = DedupEvaluator.sweep(rows,
                new double[] {0.6, 0.8});
        assertThat(sweep.get(0).recall()).isEqualTo(1.0);
        assertThat(sweep.get(0).precision()).isCloseTo(12 / 24.0, within(1e-9));
        assertThat(new DedupEvaluator.Confusion(0, 0, 0, 1).precision()).isNaN();
        assertThat(DedupEvaluator.wilson(0, 0)[0]).isNaN();
    }

    private static int counter;

    private static DedupCandidate c(String company, String title, String location) {
        counter++;
        return new DedupCandidate((long) counter, new PostingKey(Ats.LEVER, "k" + counter), company, title,
                List.of(location), null, "https://x.io/" + counter);
    }

    private static PairCsv.Row row(String stratum, double weight, String titleA, String titleB, boolean dup) {
        return new PairCsv.Row("p", stratum, weight, 0, c("Acme", titleA, "Berlin"), "", c("Acme", titleB, "Berlin"),
                "", dup, null);
    }
}
