package io.github.jozephzemambo.jobradar.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.dedup.DedupService;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.query.PostingFilter;
import io.github.jozephzemambo.jobradar.query.PostingQueryService;
import io.github.jozephzemambo.jobradar.query.PostingViews;
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import io.github.jozephzemambo.jobradar.stats.StatsService;
import io.github.jozephzemambo.jobradar.stats.StatsView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Flyway migrations, lifecycle sync, dedup, ranking and stats queries against a real Postgres. Runs in CI
 * (GitHub runners have Docker) and is skipped where Docker isn't reachable.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final Instant DAY_1 = Instant.parse("2026-10-01T06:00:00Z");

    @Autowired
    PostingStore store;

    @Autowired
    DedupService dedup;

    @Autowired
    PostingQueryService queries;

    @Autowired
    StatsService stats;

    @Test
    void fullLifecycleOnPostgres() {
        Company acme = new Company("Acme", Ats.LEVER, "acme-pg");
        store.syncBoard(acme, List.of(p("1", "Backend Engineer", 0.9), p("2", "Backend Engineer", 0.9),
                p("3", "Designer", null)), DAY_1, PostgresIntegrationTest::score);
        store.syncBoard(acme, List.of(p("1", "Backend Engineer", 0.9), p("3", "Designer", null)),
                DAY_1.plus(Duration.ofDays(1)), PostgresIntegrationTest::score);
        dedup.refresh();

        var page = queries.search(new PostingFilter(null, null, "acme", null, null, null, false),
                PostingQueryService.SortBy.SCORE, 0, 10);
        // Postgres sorts NULLs first in DESC by default; the ranked list must still put unscored postings last.
        assertThat(page.items()).extracting(PostingViews.Summary::title).containsExactly("Backend Engineer", "Designer");

        StatsView view = stats.stats(5);
        assertThat(view.openPostings()).isEqualTo(2);
        assertThat(view.closedPostings()).isEqualTo(1);
        assertThat(view.topSkills()).extracting(StatsView.SkillDemand::skill).containsExactly("Java");
    }

    private static ScoreBreakdown score(Posting p) {
        return p.descriptionText().isEmpty() ? null
                : new ScoreBreakdown(Double.parseDouble(p.descriptionText()), 1, true, false, List.of("Java"),
                        List.of("Java"), List.of());
    }

    private static Posting p(String id, String title, Double score) {
        return new Posting(Ats.LEVER, id, "Acme", title, List.of("Toronto"), null, "Eng", "https://x.io/pg/" + id,
                null, score == null ? "" : score.toString(), null, null);
    }
}
