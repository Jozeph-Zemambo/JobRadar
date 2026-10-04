package io.github.jozephzemambo.jobradar.bench;

import io.github.jozephzemambo.jobradar.dedup.DedupCandidate;
import io.github.jozephzemambo.jobradar.dedup.DedupResult;
import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import io.github.jozephzemambo.jobradar.dedup.eval.PairCsv;
import io.github.jozephzemambo.jobradar.dedup.eval.PairSampler;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.PostingKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Draws a dedup labeling sample from a crawl database:
 * {@code ./mvnw test -Dtest=DedupPairSampling -Dbench=true [-Dbench.db=jdbc:h2:...] [-Dbench.threshold=0.85]
 * [-Dbench.seed=N] [-Dbench.exclude=a.csv,b.csv] [-Dbench.out=bench/dedup/x.csv]}.
 * Seeded, so the same database and options give the same sample. {@code bench.exclude} keeps pairs already in
 * another file out of the sample, for a validation set disjoint from the development set.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class DedupPairSampling {

    static final String DEFAULT_DB =
            "jdbc:h2:file:./bench/data/jobradar;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE";
    static final long SEED = 20261002L;
    static final int SNIPPET = 280;

    @Test
    void sample() throws Exception {
        String url = System.getProperty("bench.db", DEFAULT_DB);
        List<DedupCandidate> open = new ArrayList<>();
        Map<PostingKey, String> descriptions = new HashMap<>();
        try (Connection db = DriverManager.getConnection(url, "sa", "");
             Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("""
                     select id, ats, external_id, company, title, locations, department, canonical_url, description
                     from posting where closed_at is null order by id""")) {
            while (rs.next()) {
                String locations = rs.getString("locations");
                PostingKey key = new PostingKey(Ats.valueOf(rs.getString("ats")), rs.getString("external_id"));
                open.add(new DedupCandidate(rs.getLong("id"), key, rs.getString("company"), rs.getString("title"),
                        locations == null ? List.of() : Arrays.asList(locations.split("\n")),
                        rs.getString("department"), rs.getString("canonical_url")));
                descriptions.put(key, snippet(rs.getString("description")));
            }
        }

        double threshold = Double.parseDouble(System.getProperty("bench.threshold", "1.0"));
        long seed = Long.parseLong(System.getProperty("bench.seed", Long.toString(SEED)));
        Set<Set<PostingKey>> excluded = new HashSet<>();
        String exclude = System.getProperty("bench.exclude");
        if (exclude != null) {
            for (String file : exclude.split(",")) {
                for (PairCsv.Row row : PairCsv.read(Files.readString(Path.of(file.strip())))) {
                    excluded.add(Set.of(row.a().key(), row.b().key()));
                }
            }
        }
        DedupResult flagged = new Deduplicator(threshold).dedupe(open);
        System.out.printf("Rule at threshold %.2f on %d open postings: %d exact-URL and %d fuzzy duplicates%n",
                threshold, open.size(), flagged.count(DedupResult.Reason.EXACT_URL),
                flagged.count(DedupResult.Reason.FUZZY));
        List<PairSampler.SampledPair> pairs = PairSampler.sample(open, new Deduplicator(threshold), 50, 50, seed,
                pair -> !excluded.contains(Set.of(pair.a().key(), pair.b().key())));
        List<PairCsv.Row> rows = new ArrayList<>();
        int n = 0;
        for (PairSampler.SampledPair p : pairs) {
            rows.add(new PairCsv.Row(String.format("p%03d", ++n), p.stratum(), p.weight(), p.titleSimilarity(),
                    p.a(), descriptions.get(p.a().key()), p.b(), descriptions.get(p.b().key()), null, null));
        }
        Path out = Path.of(System.getProperty("bench.out", "bench/dedup/pairs-unlabeled.csv"));
        Files.createDirectories(out.getParent());
        Files.writeString(out, PairCsv.write(rows));
        System.out.printf("%d open postings -> %d pairs written to %s%n", open.size(), rows.size(), out);
        rows.stream().collect(java.util.stream.Collectors.groupingBy(PairCsv.Row::stratum,
                java.util.stream.Collectors.summarizingDouble(PairCsv.Row::weight)))
                .forEach((stratum, stats) -> System.out.printf("  %-10s sampled=%d population=%.0f%n", stratum,
                        stats.getCount(), stats.getMax() * stats.getCount()));
    }

    private static String snippet(String description) {
        if (description == null) {
            return "";
        }
        String flat = description.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET ? flat : flat.substring(0, SNIPPET) + "...";
    }
}
