package io.github.jozephzemambo.jobradar.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

/** Runs the real Flyway schema (H2 in PostgreSQL mode) rather than a Hibernate-generated one. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostingStore.class)
class PostingStoreTest {

    private static final Function<Posting, ScoreBreakdown> UNSCORED = p -> null;

    private static final Instant DAY_1 = Instant.parse("2026-10-01T06:00:00Z");
    private static final Instant DAY_2 = DAY_1.plus(Duration.ofDays(1));
    private static final Instant DAY_3 = DAY_2.plus(Duration.ofDays(1));

    private final Company palantir = new Company("Palantir", Ats.LEVER, "palantir");

    @Autowired
    PostingStore store;

    @Autowired
    PostingRepository repository;

    @Autowired
    TestEntityManager em;

    @Test
    void firstSyncCreatesEverything() {
        SyncCounts counts = store.syncBoard(palantir, List.of(posting("a", "Deployment Strategist"),
                posting("b", "Forward Deployed Engineer")), DAY_1, UNSCORED);

        assertThat(counts).isEqualTo(new SyncCounts(2, 0, 0, 0));
        PostingEntity a = find("a");
        assertThat(a.getFirstSeenAt()).isEqualTo(DAY_1);
        assertThat(a.getLastSeenAt()).isEqualTo(DAY_1);
        assertThat(a.isOpen()).isTrue();
        assertThat(a.getNormalizedTitle()).isEqualTo("deployment strategist");
        assertThat(a.getLocations()).containsExactly("London, United Kingdom", "Remote");
        assertThat(a.toPosting()).isEqualTo(posting("a", "Deployment Strategist"));
    }

    @Test
    void resyncingTheSameListingIsIdempotent() {
        store.syncBoard(palantir, List.of(posting("a", "Strategist")), DAY_1, UNSCORED);
        flushAndClear();

        SyncCounts counts = store.syncBoard(palantir, List.of(posting("a", "Strategist")), DAY_2, UNSCORED);
        flushAndClear();

        assertThat(counts).isEqualTo(new SyncCounts(0, 1, 0, 0));
        assertThat(repository.count()).isEqualTo(1);
        PostingEntity a = find("a");
        assertThat(a.getFirstSeenAt()).isEqualTo(DAY_1);
        assertThat(a.getLastSeenAt()).isEqualTo(DAY_2);
    }

    @Test
    void postingMissingFromALaterListingIsClosedAndReopensIfItReturns() {
        store.syncBoard(palantir, List.of(posting("a", "A"), posting("b", "B")), DAY_1, UNSCORED);
        flushAndClear();

        SyncCounts day2 = store.syncBoard(palantir, List.of(posting("a", "A")), DAY_2, UNSCORED);
        flushAndClear();
        assertThat(day2).isEqualTo(new SyncCounts(0, 1, 0, 1));
        assertThat(find("b").getClosedAt()).isEqualTo(DAY_2);

        SyncCounts day3 = store.syncBoard(palantir, List.of(posting("a", "A"), posting("b", "B")), DAY_3, UNSCORED);
        flushAndClear();
        assertThat(day3).isEqualTo(new SyncCounts(0, 1, 1, 0));
        assertThat(find("b").isOpen()).isTrue();
        assertThat(find("b").getFirstSeenAt()).as("first seen survives a close/reopen").isEqualTo(DAY_1);
    }

    @Test
    void alreadyClosedPostingIsNotClosedAgain() {
        store.syncBoard(palantir, List.of(posting("a", "A")), DAY_1, UNSCORED);
        store.syncBoard(palantir, List.of(), DAY_2, UNSCORED);
        flushAndClear();

        SyncCounts counts = store.syncBoard(palantir, List.of(), DAY_3, UNSCORED);

        assertThat(counts.closed()).isZero();
        assertThat(find("a").getClosedAt()).isEqualTo(DAY_2);
    }

    @Test
    void otherBoardsAreUntouched() {
        Company spotify = new Company("Spotify", Ats.LEVER, "spotify");
        store.syncBoard(palantir, List.of(posting("a", "A")), DAY_1, UNSCORED);
        store.syncBoard(spotify, List.of(posting("s", "S")), DAY_1, UNSCORED);
        flushAndClear();

        store.syncBoard(spotify, List.of(), DAY_2, UNSCORED);
        flushAndClear();

        assertThat(find("a").isOpen()).isTrue();
        assertThat(find("s").isOpen()).isFalse();
    }

    @Test
    void duplicateIdsInOneResponseAreStoredOnce() {
        SyncCounts counts = store.syncBoard(palantir, List.of(posting("a", "A"), posting("a", "A again")), DAY_1, UNSCORED);

        assertThat(counts.created()).isEqualTo(1);
        assertThat(find("a").getTitle()).isEqualTo("A");
    }

    @Test
    void laterFetchWithoutDescriptionKeepsStoredOneAndScoresTheMergedPosting() {
        // Paged sources only fetch details for the first N postings, so a posting can arrive undescribed.
        store.syncBoard(palantir, List.of(posting("a", "A")), DAY_1, UNSCORED);
        flushAndClear();
        Posting undescribed = new Posting(Ats.LEVER, "a", "Palantir", "A", List.of(), null, null,
                "https://jobs.lever.co/palantir/a", null, "", null, null);
        List<String> scoredDescriptions = new java.util.ArrayList<>();

        store.syncBoard(palantir, List.of(undescribed), DAY_2, p -> {
            scoredDescriptions.add(p.descriptionText());
            return new ScoreBreakdown(0.9, 0.9, true, false, List.of("Java"), List.of("Java"), List.of());
        });
        flushAndClear();

        PostingEntity a = find("a");
        assertThat(a.getDescription()).isEqualTo("Work with Java and SQL");
        assertThat(scoredDescriptions).containsExactly("Work with Java and SQL");
        assertThat(a.getScore()).isEqualTo(0.9);
        assertThat(a.getSkills()).containsExactly("Java");
        assertThat(a.getMatchedSkills()).containsExactly("Java");
    }

    @Test
    void entityEqualityIsById() {
        store.syncBoard(palantir, List.of(posting("a", "A"), posting("b", "B")), DAY_1, UNSCORED);
        flushAndClear();
        PostingEntity a1 = find("a");
        flushAndClear();
        PostingEntity a2 = find("a");

        assertThat(a1).isEqualTo(a2).hasSameHashCodeAs(a2).isNotEqualTo(find("b")).isNotEqualTo("a");
        assertThat(PostingEntity.create(posting("x", "X"), "t", DAY_1))
                .isNotEqualTo(PostingEntity.create(posting("x", "X"), "t", DAY_1));
    }

    private PostingEntity find(String externalId) {
        return repository.findByAtsAndBoardToken(Ats.LEVER, "palantir").stream()
                .filter(e -> e.getExternalId().equals(externalId))
                .findFirst()
                .or(() -> repository.findByAtsAndBoardToken(Ats.LEVER, "spotify").stream()
                        .filter(e -> e.getExternalId().equals(externalId)).findFirst())
                .orElseThrow();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    private static Posting posting(String id, String title) {
        return new Posting(Ats.LEVER, id, "Palantir", title, List.of("London, United Kingdom", "Remote"),
                WorkplaceType.HYBRID, "Business", "https://jobs.lever.co/palantir/" + id,
                "https://jobs.lever.co/palantir/" + id, "Work with Java and SQL", DAY_1.minus(Duration.ofDays(400)),
                null);
    }
}
