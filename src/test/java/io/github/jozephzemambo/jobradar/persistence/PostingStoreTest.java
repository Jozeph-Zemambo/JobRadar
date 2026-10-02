package io.github.jozephzemambo.jobradar.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.scoring.ScoredPosting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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
        SyncCounts counts = store.syncBoard(palantir, scored(posting("a", "Deployment Strategist"),
                posting("b", "Forward Deployed Engineer")), DAY_1);

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
        store.syncBoard(palantir, scored(posting("a", "Strategist")), DAY_1);
        flushAndClear();

        SyncCounts counts = store.syncBoard(palantir, scored(posting("a", "Strategist")), DAY_2);
        flushAndClear();

        assertThat(counts).isEqualTo(new SyncCounts(0, 1, 0, 0));
        assertThat(repository.count()).isEqualTo(1);
        PostingEntity a = find("a");
        assertThat(a.getFirstSeenAt()).isEqualTo(DAY_1);
        assertThat(a.getLastSeenAt()).isEqualTo(DAY_2);
    }

    @Test
    void postingMissingFromALaterListingIsClosedAndReopensIfItReturns() {
        store.syncBoard(palantir, scored(posting("a", "A"), posting("b", "B")), DAY_1);
        flushAndClear();

        SyncCounts day2 = store.syncBoard(palantir, scored(posting("a", "A")), DAY_2);
        flushAndClear();
        assertThat(day2).isEqualTo(new SyncCounts(0, 1, 0, 1));
        assertThat(find("b").getClosedAt()).isEqualTo(DAY_2);

        SyncCounts day3 = store.syncBoard(palantir, scored(posting("a", "A"), posting("b", "B")), DAY_3);
        flushAndClear();
        assertThat(day3).isEqualTo(new SyncCounts(0, 1, 1, 0));
        assertThat(find("b").isOpen()).isTrue();
        assertThat(find("b").getFirstSeenAt()).as("first seen survives a close/reopen").isEqualTo(DAY_1);
    }

    @Test
    void alreadyClosedPostingIsNotClosedAgain() {
        store.syncBoard(palantir, scored(posting("a", "A")), DAY_1);
        store.syncBoard(palantir, scored(), DAY_2);
        flushAndClear();

        SyncCounts counts = store.syncBoard(palantir, scored(), DAY_3);

        assertThat(counts.closed()).isZero();
        assertThat(find("a").getClosedAt()).isEqualTo(DAY_2);
    }

    @Test
    void otherBoardsAreUntouched() {
        Company spotify = new Company("Spotify", Ats.LEVER, "spotify");
        store.syncBoard(palantir, scored(posting("a", "A")), DAY_1);
        store.syncBoard(spotify, scored(posting("s", "S")), DAY_1);
        flushAndClear();

        store.syncBoard(spotify, scored(), DAY_2);
        flushAndClear();

        assertThat(find("a").isOpen()).isTrue();
        assertThat(find("s").isOpen()).isFalse();
    }

    @Test
    void duplicateIdsInOneResponseAreStoredOnce() {
        SyncCounts counts = store.syncBoard(palantir, scored(posting("a", "A"), posting("a", "A again")), DAY_1);

        assertThat(counts.created()).isEqualTo(1);
        assertThat(find("a").getTitle()).isEqualTo("A");
    }

    @Test
    void entityEqualityIsById() {
        store.syncBoard(palantir, scored(posting("a", "A"), posting("b", "B")), DAY_1);
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

    private static List<ScoredPosting> scored(Posting... postings) {
        return Arrays.stream(postings).map(p -> new ScoredPosting(p, null)).toList();
    }
}
