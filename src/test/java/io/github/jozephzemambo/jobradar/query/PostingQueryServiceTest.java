package io.github.jozephzemambo.jobradar.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.dedup.DedupService;
import io.github.jozephzemambo.jobradar.dedup.Deduplicator;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.persistence.PostingStore;
import io.github.jozephzemambo.jobradar.query.PostingFilter.PostingStatus;
import io.github.jozephzemambo.jobradar.query.PostingQueryService.SortBy;
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostingStore.class, DedupService.class, PostingQueryService.class, PostingQueryServiceTest.Config.class})
class PostingQueryServiceTest {

    private static final Instant DAY_1 = Instant.parse("2026-10-01T06:00:00Z");
    private static final Instant DAY_2 = DAY_1.plus(Duration.ofDays(1));

    private static final Company RAMP = new Company("Ramp", Ats.ASHBY, "ramp");
    private static final Company STRIPE = new Company("Stripe", Ats.GREENHOUSE, "stripe");
    private static final Company NEVER_FETCHED = new Company("Linear", Ats.ASHBY, "linear");

    @TestConfiguration
    static class Config {
        @Bean
        Deduplicator deduplicator() {
            return new Deduplicator(0.8);
        }

        @Bean
        JobRadarProperties props() {
            return new JobRadarProperties(null, null, null, List.of(RAMP, STRIPE, NEVER_FETCHED), null, null);
        }
    }

    /** Scores taken from a lookup so each posting's rank is known in advance. */
    private static final Map<String, Double> SCORES = Map.of("r1", 0.9, "r2", 0.4, "s1", 0.7, "s2", 0.7);

    @Autowired
    PostingStore store;

    @Autowired
    DedupService dedup;

    @Autowired
    PostingQueryService queries;

    @Autowired
    TestEntityManager em;

    @BeforeEach
    void seed() {
        store.syncBoard(RAMP, List.of(p(Ats.ASHBY, "r1", "Ramp", "Senior Software Engineer, Backend", "New York"),
                p(Ats.ASHBY, "r2", "Ramp", "Account Executive", "Miami")), DAY_1, PostingQueryServiceTest::score);
        store.syncBoard(STRIPE, List.of(p(Ats.GREENHOUSE, "s1", "Stripe", "Abuse Investigator", "Dublin"),
                p(Ats.GREENHOUSE, "s2", "Stripe", "Abuse Investigator", "Dublin"),
                p(Ats.GREENHOUSE, "s3", "Stripe", "Recruiter", "Dublin")), DAY_2, PostingQueryServiceTest::score);
        store.syncBoard(RAMP, List.of(p(Ats.ASHBY, "r1", "Ramp", "Senior Software Engineer, Backend", "New York")),
                DAY_2, PostingQueryServiceTest::score);
        em.flush();
        dedup.refresh();
        em.clear();
    }

    @Test
    void defaultListIsOpenNonDuplicateRankedByScoreWithUnscoredLast() {
        List<String> ids = externalIds(new PostingFilter(null, null, null, null, null, null, false), SortBy.SCORE);

        // r2 closed on day 2; s2 duplicates s1; s3 has no score so it sorts last.
        assertThat(ids).containsExactly("r1", "s1", "s3");
    }

    @Test
    void filtersCombine() {
        assertThat(externalIds(new PostingFilter(0.5, null, null, null, null, null, false), SortBy.SCORE))
                .containsExactly("r1", "s1");
        assertThat(externalIds(new PostingFilter(null, DAY_2, null, null, null, null, false), SortBy.SCORE))
                .as("first seen on day 2").containsExactly("s1", "s3");
        assertThat(externalIds(new PostingFilter(null, null, "stripe", null, PostingStatus.ALL, null, true), SortBy.SCORE))
                .containsExactlyInAnyOrder("s1", "s2", "s3");
        assertThat(externalIds(new PostingFilter(null, null, null, Ats.ASHBY, PostingStatus.CLOSED, null, false), SortBy.SCORE))
                .containsExactly("r2");
        assertThat(externalIds(new PostingFilter(null, null, null, null, null, "sr. software eng", false), SortBy.SCORE))
                .containsExactly("r1");
        assertThat(externalIds(new PostingFilter(null, null, null, null, null, "100%_", false), SortBy.SCORE))
                .as("LIKE wildcards are escaped").isEmpty();
    }

    @Test
    void newestSortAndPaging() {
        PostingViews.PageOf<PostingViews.Summary> page = queries.search(
                new PostingFilter(null, null, null, null, PostingStatus.ALL, null, true), SortBy.NEWEST, 1, 2);

        assertThat(page.totalItems()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.items()).hasSize(2);
        assertThatThrownBy(() -> queries.search(new PostingFilter(null, null, null, null, null, null, false),
                SortBy.SCORE, 0, 500)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailExplainsScoreAndListsDuplicates() {
        long s1 = idOf("s1");

        PostingViews.Detail detail = queries.detail(s1);

        assertThat(detail.skills()).containsExactly("Java", "Kafka");
        assertThat(detail.missingSkills()).containsExactly("Kafka");
        assertThat(detail.duplicateIds()).containsExactly(idOf("s2"));
        assertThat(detail.description()).isEqualTo("Java and Kafka");
        assertThatThrownBy(() -> queries.detail(-1)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void boardsIncludeNeverFetchedCompaniesWithZeros() {
        assertThat(queries.boards()).containsExactly(
                new PostingViews.Board("Ramp", Ats.ASHBY, "ramp", 2, 1, DAY_2),
                new PostingViews.Board("Stripe", Ats.GREENHOUSE, "stripe", 3, 3, DAY_2),
                new PostingViews.Board("Linear", Ats.ASHBY, "linear", 0, 0, null));
    }

    @Test
    void scoreOutsideZeroToOneIsRejected() {
        assertThatThrownBy(() -> new PostingFilter(1.5, null, null, null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostingFilter(Double.NaN, null, null, null, null, null, false))
                .as("NaN fails every comparison, so it needs its own check").isInstanceOf(IllegalArgumentException.class);
    }

    private List<String> externalIds(PostingFilter filter, SortBy sort) {
        return queries.search(filter, sort, 0, 50).items().stream()
                .map(s -> s.url().substring(s.url().lastIndexOf('/') + 1))
                .toList();
    }

    private long idOf(String externalId) {
        return queries.search(new PostingFilter(null, null, null, null, PostingStatus.ALL, null, true), SortBy.SCORE,
                0, 50).items().stream().filter(s -> s.url().endsWith("/" + externalId)).findFirst().orElseThrow().id();
    }

    private static ScoreBreakdown score(Posting p) {
        Double score = SCORES.get(p.externalId());
        return score == null ? null
                : new ScoreBreakdown(score, score, true, false, List.of("Java", "Kafka"), List.of("Java"), List.of("Kafka"));
    }

    private static Posting p(Ats ats, String id, String company, String title, String location) {
        return new Posting(ats, id, company, title, List.of(location), null, "Team", "https://x.io/" + id, null,
                "Java and Kafka", null, null);
    }
}
