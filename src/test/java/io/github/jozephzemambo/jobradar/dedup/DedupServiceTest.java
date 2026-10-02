package io.github.jozephzemambo.jobradar.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.persistence.PostingEntity;
import io.github.jozephzemambo.jobradar.persistence.PostingRepository;
import io.github.jozephzemambo.jobradar.persistence.PostingStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
@Import({PostingStore.class, DedupService.class, DedupServiceTest.Config.class})
class DedupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T06:00:00Z");

    @TestConfiguration
    static class Config {
        @Bean
        Deduplicator deduplicator() {
            return new Deduplicator(new TitleTokenJaccard(), 0.8);
        }
    }

    private final Company stripe = new Company("Stripe", Ats.GREENHOUSE, "stripe");

    @Autowired
    PostingStore store;

    @Autowired
    DedupService dedupService;

    @Autowired
    PostingRepository repository;

    @Autowired
    TestEntityManager em;

    @Test
    void linksDuplicatesInTheDatabaseAndRecomputesOnEveryRun() {
        store.syncBoard(stripe, List.of(
                posting("1", "Abuse Investigator", "Dublin"),
                posting("2", "Abuse Investigator", "Dublin"),
                posting("3", "Abuse Investigator", "Seattle")), NOW);
        em.flush();

        DedupResult first = dedupService.refresh();
        em.clear();

        assertThat(first.candidates()).isEqualTo(3);
        assertThat(first.count(DedupResult.Reason.FUZZY)).isEqualTo(1);
        Map<String, PostingEntity> byId = byExternalId();
        assertThat(byId.get("2").getDuplicateOfId()).isEqualTo(byId.get("1").getId());
        assertThat(byId.get("2").getDedupReason()).isEqualTo(DedupResult.Reason.FUZZY);
        assertThat(byId.get("1").getDuplicateOfId()).isNull();
        assertThat(byId.get("3").getDuplicateOfId()).isNull();

        // The canonical posting closes: the remaining one is no longer a duplicate of anything open.
        store.syncBoard(stripe, List.of(posting("2", "Abuse Investigator", "Dublin"),
                posting("3", "Abuse Investigator", "Seattle")), NOW.plusSeconds(86_400));
        em.flush();
        DedupResult second = dedupService.refresh();
        em.clear();

        assertThat(second.duplicates()).isEmpty();
        assertThat(byExternalId().get("2").getDuplicateOfId()).isNull();
    }

    @Test
    void loadsLocationsThroughTheConverterInAProjection() {
        store.syncBoard(stripe, List.of(posting("1", "Designer", "Dublin")), NOW);
        em.flush();

        assertThat(dedupService.loadOpenCandidates()).singleElement().satisfies(c -> {
            assertThat(c.locations()).containsExactly("Dublin", "Remote (EU)");
            assertThat(c.key().externalId()).isEqualTo("1");
            assertThat(c.id()).isNotNull();
        });
    }

    private Map<String, PostingEntity> byExternalId() {
        return repository.findAll().stream().collect(Collectors.toMap(PostingEntity::getExternalId, e -> e));
    }

    private static Posting posting(String id, String title, String location) {
        return new Posting(Ats.GREENHOUSE, id, "Stripe", title, List.of(location, "Remote (EU)"), null,
                "8611 Security Analytics", "https://stripe.com/jobs/search?gh_jid=" + id, null, "", null, null);
    }
}
