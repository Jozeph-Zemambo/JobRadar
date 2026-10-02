package io.github.jozephzemambo.jobradar.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jozephzemambo.jobradar.dedup.DedupService;
import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.ingest.FetchMode;
import io.github.jozephzemambo.jobradar.persistence.IngestRunEntity;
import io.github.jozephzemambo.jobradar.persistence.IngestRunRepository;
import io.github.jozephzemambo.jobradar.persistence.PostingStore;
import io.github.jozephzemambo.jobradar.persistence.SyncCounts;
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import io.github.jozephzemambo.jobradar.scoring.SkillDictionary;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostingStore.class, DedupService.class, StatsService.class, ExportService.class, StatsServiceTest.Config.class})
class StatsServiceTest {

    private static final Instant DAY_0 = Instant.parse("2026-09-01T06:00:00Z");
    private static final Instant NOW = DAY_0.plus(Duration.ofDays(20));

    @TestConfiguration
    static class Config {
        @Bean
        Deduplicator deduplicator() {
            return new Deduplicator(0.8);
        }

        @Bean
        SkillDictionary dictionary() {
            return new SkillDictionary(List.of(new SkillDictionary.Skill("Java", "language", null, null),
                    new SkillDictionary.Skill("Python", "language", null, null),
                    new SkillDictionary.Skill("Kafka", "data", null, null)));
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        JsonMapper mapper() {
            return JsonMapper.builder().build();
        }
    }

    private final Company acme = new Company("Acme", Ats.LEVER, "acme");
    private final Company globex = new Company("Globex", Ats.ASHBY, "globex");

    @Autowired
    PostingStore store;

    @Autowired
    DedupService dedup;

    @Autowired
    IngestRunRepository runs;

    @Autowired
    StatsService stats;

    @Autowired
    ExportService export;

    @Autowired
    TestEntityManager em;

    @BeforeEach
    void seed() {
        // Day 0: first crawl. "old" was already open, so its true start is unknown.
        run(DAY_0);
        store.syncBoard(acme, List.of(p("old", "Acme", "Analyst", WorkplaceType.ONSITE, "Python")), DAY_0, this::skills);
        // Day 2: two new openings observed; Globex posts the same role twice.
        Instant day2 = DAY_0.plus(Duration.ofDays(2));
        run(day2);
        store.syncBoard(acme, List.of(p("old", "Acme", "Analyst", WorkplaceType.ONSITE, "Python"),
                p("a", "Acme", "Backend Engineer", WorkplaceType.REMOTE, "Java Kafka"),
                p("b", "Acme", "Data Engineer", WorkplaceType.REMOTE, "Python Kafka")), day2, this::skills);
        store.syncBoard(globex, List.of(p("g1", "Globex", "Java Developer", WorkplaceType.HYBRID, "Java"),
                p("g2", "Globex", "Java Developer", WorkplaceType.HYBRID, "Java")), day2, this::skills);
        // Day 6: "old" and "a" close. "a" was open 4 days; "old" is excluded (left-censored).
        Instant day6 = DAY_0.plus(Duration.ofDays(6));
        run(day6);
        store.syncBoard(acme, List.of(p("b", "Acme", "Data Engineer", WorkplaceType.REMOTE, "Python Kafka")), day6,
                this::skills);
        // Day 19: "b" closes after 17 days; "n" is new.
        Instant day19 = DAY_0.plus(Duration.ofDays(19));
        run(day19);
        store.syncBoard(acme, List.of(p("n", "Acme", "Platform Engineer", WorkplaceType.REMOTE, "Java")), day19,
                this::skills);
        em.flush();
        dedup.refresh();
        em.clear();
    }

    @Test
    void snapshotCounts() {
        StatsView view = stats.stats(10);

        assertThat(view.openPostings()).isEqualTo(2); // n, g1 (g2 duplicates g1)
        assertThat(view.closedPostings()).isEqualTo(3);
        assertThat(view.openDuplicates()).isEqualTo(1);
        assertThat(view.openByAts()).isEqualTo(Map.of("LEVER", 1L, "ASHBY", 1L));
        assertThat(view.openByWorkplace()).containsEntry("REMOTE", 1L).containsEntry("HYBRID", 1L);
        assertThat(view.topCompanies()).extracting(StatsView.Count::name).containsExactly("Acme", "Globex");
        assertThat(view.crawl().runs()).isEqualTo(4);
        assertThat(view.crawl().firstRunAt()).isEqualTo(DAY_0);
        assertThat(view.newLast7Days()).as("only 'n' was first seen in the last week").isEqualTo(1);
    }

    @Test
    void timeToCloseIgnoresPostingsAlreadyOpenAtTheFirstCrawl() {
        StatsView.TimeToClose ttc = stats.stats(10).timeToClose();

        assertThat(ttc.observedClosures()).isEqualTo(2); // a (4 days) and b (17 days), not "old"
        assertThat(ttc.medianDays()).isEqualTo(10.5);
        assertThat(ttc.p75Days()).isEqualTo(13.75);
    }

    @Test
    void boardAddedLaterDoesNotCountItsBacklogAsObservedOpenings() {
        // Initech is first synced on day 10; its posting was already open then, so its start is unknown.
        Company initech = new Company("Initech", Ats.ASHBY, "initech");
        store.syncBoard(initech, List.of(p("i1", "Initech", "Analyst", null, "Java")), DAY_0.plus(Duration.ofDays(10)),
                this::skills);
        store.syncBoard(initech, List.of(), DAY_0.plus(Duration.ofDays(13)), this::skills);
        em.flush();

        StatsView view = stats.stats(10);

        assertThat(view.closedPostings()).isEqualTo(4);
        assertThat(view.timeToClose().observedClosures()).as("still only a and b").isEqualTo(2);
        assertThat(view.newLast7Days()).isEqualTo(1);
    }

    @Test
    void exportUsesKeysetSoAConcurrentChangeCannotSkipRows() throws Exception {
        List<Posting> many = new java.util.ArrayList<>();
        for (int i = 0; i < ExportService.BATCH + 100; i++) {
            many.add(p("bulk" + i, "Bulkco", "Role " + i, null, "Java"));
        }
        store.syncBoard(new Company("Bulkco", Ats.LEVER, "bulkco"), many, NOW, this::skills);
        em.flush();
        long before = export.export(null, null, new ByteArrayOutputStream());
        // After the first batch is written, an ingest closes a posting that was already exported. With offset
        // paging the next page would start one row later and silently skip an eligible posting.
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                if (changed.compareAndSet(false, true)) {
                    em.getEntityManager().createNativeQuery(
                            "update posting set closed_at = current_timestamp where id = (select min(id) from posting "
                                    + "where closed_at is null and duplicate_of_id is null)").executeUpdate();
                }
            }
        };

        long written = export.export(null, null, out);

        assertThat(written).isEqualTo(before);
        assertThat(out.toString(StandardCharsets.UTF_8).lines().distinct().count()).isEqualTo(before);
    }

    @Test
    void skillDemandAcrossOpenNonDuplicatePostings() {
        List<StatsView.SkillDemand> skills = stats.stats(10).topSkills();

        assertThat(skills).containsExactly(new StatsView.SkillDemand("Java", "language", 2, 1.0));
        assertThat(stats.stats(1).topSkills()).hasSize(1);
        assertThatThrownBy(() -> stats.stats(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topSkillsKeepsTheLargestCountsWithStableTieBreak() {
        // Reopen everything so more skills are open: Java 3 (a, n, g1), Python 2 (old, b), Kafka 2 (a, b).
        store.syncBoard(acme, List.of(p("old", "Acme", "Analyst", null, "Python"),
                p("a", "Acme", "Backend Engineer", null, "Java Kafka"),
                p("b", "Acme", "Data Engineer", null, "Python Kafka"),
                p("n", "Acme", "Platform Engineer", null, "Java")), NOW, this::skills);
        em.flush();

        assertThat(stats.topSkills(2, 5)).extracting(StatsView.SkillDemand::skill).containsExactly("Java", "Kafka");
    }

    @Test
    void percentileInterpolates() {
        assertThat(StatsService.percentile(List.of(1.0, 2.0, 3.0, 4.0), 0.5)).isEqualTo(2.5);
        assertThat(StatsService.percentile(List.of(7.0), 0.75)).isEqualTo(7.0);
    }

    @Test
    void exportWritesOneJsonLinePerOpenNonDuplicatePosting() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        long written = export.export(null, null, out);

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertThat(written).isEqualTo(2);
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("\"title\":\"Java Developer\"").contains("\"description\":\"Java\"");
        assertThat(export.export(null, NOW, new ByteArrayOutputStream())).isZero();
        assertThat(export.export(0.99, null, new ByteArrayOutputStream())).isZero();
    }

    private void run(Instant at) {
        runs.save(new IngestRunEntity(at, FetchMode.VIRTUAL, 2, 2, 0, SyncCounts.ZERO, 0, 0, 0));
    }

    /** "Scores" a posting by listing which seeded skill words its description contains. */
    private ScoreBreakdown skills(Posting p) {
        List<String> found = List.of(p.descriptionText().split(" "));
        return new ScoreBreakdown(0.5, 0.5, false, false, found, found, List.of());
    }

    private static Posting p(String id, String company, String title, WorkplaceType workplace, String skills) {
        return new Posting(company.equals("Acme") ? Ats.LEVER : Ats.ASHBY, id, company, title, List.of("Toronto"),
                workplace, "Eng", "https://x.io/" + id, null, skills, null, null);
    }
}
