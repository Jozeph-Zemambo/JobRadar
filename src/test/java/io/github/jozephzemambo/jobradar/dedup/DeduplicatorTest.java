package io.github.jozephzemambo.jobradar.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.PostingKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeduplicatorTest {

    private final Deduplicator dedup = new Deduplicator(0.8);
    private final AtomicLong ids = new AtomicLong();

    @Test
    void sameRolePostedTwiceIsAFuzzyDuplicate() {
        // Real pair from Stripe's board on 2026-10-02: two reqs, same role, team and city, minutes apart.
        DedupCandidate first = c("Stripe", "Abuse Investigator", "Dublin", "8611 Security Analytics");
        DedupCandidate second = c("Stripe", "Abuse Investigator", "Dublin", "8611 Security Analytics");

        DedupResult result = dedup.dedupe(List.of(second, first));

        assertThat(result.duplicates()).singleElement().satisfies(d -> {
            assertThat(d.duplicate()).isEqualTo(second);
            assertThat(d.canonical()).as("lower id = seen earlier = canonical").isEqualTo(first);
            assertThat(d.reason()).isEqualTo(DedupResult.Reason.FUZZY);
            assertThat(d.titleSimilarity()).isEqualTo(1.0);
        });
    }

    @Test
    void sameTitleInElevenCitiesIsElevenOpenings() {
        // Real: Palantir lists "Deployment Strategist" in 11 cities.
        List<String> cities = List.of("Seoul, South Korea", "Copenhagen, Denmark", "Sydney, Australia",
                "Tel Aviv, Israel", "Vilnius, Lithuania", "London, United Kingdom", "Oslo, Norway", "Chicago, IL",
                "Abu Dhabi, United Arab Emirates", "New York, NY", "Singapore, Singapore");
        List<DedupCandidate> postings = cities.stream()
                .map(city -> c("Palantir", "Deployment Strategist", city, "Echo"))
                .toList();

        assertThat(dedup.dedupe(postings).duplicates()).isEmpty();
    }

    @ParameterizedTest(name = "{0} | {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Senior Software Engineer, Backend | Staff Software Engineer, Backend | New York      | New York | Eng  | Eng
            Android Engineer                  | iOS Engineer                     | London        | London   | Eng  | Eng
            Software Engineer                 | Software Engineer                | London        | London   | Ads  | Search
            Software Engineer II              | Software Engineer III            | Remote (US)   | US-Remote| Eng  | Eng
            Account Executive                 | Account Executive                | Remote (US)   | Dublin   | Sales| Sales
            Software Engineer, Consumer Revenue | Senior Software Engineer, Consumer Revenue | SF | SF | Eng | Eng
            Engineer, Platform                | Engineer, Platform               | ''            | ''       | Eng  | Eng
            """)
    void hardNegativesAreNotMerged(String titleA, String titleB, String locA, String locB, String depA,
            String depB) {
        DedupResult result = dedup.dedupe(List.of(c("Co", titleA, locA, depA), c("Co", titleB, locB, depB)));
        assertThat(result.duplicates()).isEmpty();
    }

    @ParameterizedTest(name = "{0} | {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Engineer, Backend           | Backend Engineer            | New York, NY (HQ) | NYC
            Sr. Software Eng - Payments | Senior Software Engineer, Payments | London, United Kingdom | London
            Account Executive           | Account Executive           | US-Remote         | Remote (US)
            """)
    void formattingVariantsAreMerged(String titleA, String titleB, String locA, String locB) {
        DedupResult result = dedup.dedupe(List.of(c("Co", titleA, locA, null), c("Co", titleB, locB, "Eng")));
        assertThat(result.count(DedupResult.Reason.FUZZY)).isEqualTo(1);
    }

    @Test
    void oneExtraSpecializationWordMeansADifferentOpeningAtTheDefaultThreshold() {
        // Real held-out false positives under the 0.85 rule (Shield AI, Ubisoft).
        Deduplicator strict = new Deduplicator(1.0);
        DedupCandidate weapons = c("Shield AI", "Senior Software Engineer, Autonomous Pilot Integration - Weapons (R5427)",
                "Washington, D.C.", "Hivemind");
        DedupCandidate general = c("Shield AI", "Senior Software Engineer, Autonomous Pilot Integration (R5200)",
                "Washington, D.C.", "Hivemind");
        DedupCandidate reqA = c("Shield AI", "Staff Engineer, Mechanical Design (R4571)", "Seattle", "Hivemind");
        DedupCandidate reqB = c("Shield AI", "Staff Engineer, Mechanical Design (R4572)", "Seattle", "Hivemind");

        assertThat(strict.isFuzzyMatch(weapons, general)).isFalse();
        assertThat(strict.isFuzzyMatch(reqA, reqB)).as("only the requisition id differs").isTrue();
    }

    @Test
    void differentCompaniesAreNeverFuzzyMatched() {
        DedupCandidate a = c("Ramp", "Software Engineer, Frontend", "New York", "Engineering");
        DedupCandidate b = c("Notion", "Software Engineer, Frontend", "New York", "Engineering");

        assertThat(dedup.dedupe(List.of(a, b)).duplicates()).isEmpty();
        assertThat(dedup.isFuzzyMatch(a, b)).isFalse();
    }

    @Test
    void sameUrlOnTwoBoardsIsAnExactDuplicateEvenAcrossCompanyNames() {
        DedupCandidate lever = new DedupCandidate(1L, new PostingKey(Ats.LEVER, "x"), "Cohere", "ML Engineer",
                List.of("Toronto"), null, "https://cohere.com/careers/ml-engineer");
        DedupCandidate ashby = new DedupCandidate(2L, new PostingKey(Ats.ASHBY, "y"), "Cohere AI", "ML Eng",
                List.of(), null, "https://cohere.com/careers/ml-engineer");

        DedupResult result = dedup.dedupe(List.of(ashby, lever));

        assertThat(result.duplicates()).singleElement().satisfies(d -> {
            assertThat(d.reason()).isEqualTo(DedupResult.Reason.EXACT_URL);
            assertThat(d.canonical()).isEqualTo(lever);
        });
    }

    @Test
    void exactThenFuzzyChainPointsAtTheRoot() {
        // 3 shares 2's URL (exact), and 2 fuzzily matches 1: both must point at 1, not 3 -> 2 -> 1.
        DedupCandidate one = new DedupCandidate(1L, new PostingKey(Ats.LEVER, "1"), "Co", "Data Engineer",
                List.of("Berlin"), null, "https://x.io/A");
        DedupCandidate two = new DedupCandidate(2L, new PostingKey(Ats.LEVER, "2"), "Co", "Data Engineer",
                List.of("Berlin"), null, "https://x.io/B");
        DedupCandidate three = new DedupCandidate(3L, new PostingKey(Ats.LEVER, "3"), "Co", "Data Engineer",
                List.of("Berlin"), null, "https://x.io/B");

        DedupResult result = dedup.dedupe(List.of(three, two, one));

        assertThat(result.duplicates()).hasSize(2).allSatisfy(d -> assertThat(d.canonical()).isEqualTo(one));
        assertThat(result.duplicates()).filteredOn(d -> d.duplicate().equals(three))
                .extracting(DedupResult.Duplicate::reason).containsExactly(DedupResult.Reason.EXACT_URL);
    }

    @Test
    void clusteringIsNotTransitive() {
        // A~B (0.75) and B~C (0.8) at threshold 0.7, but A~C is only 0.6: C must not be chained onto A.
        // (No level words in these titles, so only similarity decides.)
        Deduplicator loose = new Deduplicator(0.7);
        DedupCandidate a = c("Co", "alpha beta gamma", "Paris", null);
        DedupCandidate b = c("Co", "alpha beta gamma delta", "Paris", null);
        DedupCandidate cc = c("Co", "alpha beta gamma delta epsilon", "Paris", null);

        DedupResult result = loose.dedupe(List.of(a, b, cc));

        assertThat(result.duplicates()).extracting(DedupResult.Duplicate::duplicate).containsExactly(b);
    }

    @Test
    void canonicalChoiceDoesNotDependOnInputOrder() {
        List<DedupCandidate> postings = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            postings.add(c("Co", "Data Engineer", "Berlin", "Data"));
        }
        DedupCandidate oldest = postings.getFirst();
        Collections.shuffle(postings);

        DedupResult result = dedup.dedupe(postings);

        assertThat(result.duplicates()).hasSize(4).allSatisfy(d -> assertThat(d.canonical()).isEqualTo(oldest));
    }

    @Test
    void blockingByCompanyLimitsComparisons() {
        List<DedupCandidate> postings = new ArrayList<>();
        for (String company : List.of("A", "B")) {
            for (String title : List.of("Designer", "Recruiter", "Lawyer")) {
                postings.add(c(company, title, "Paris", null));
            }
        }

        // Each block of 3 distinct titles compares 0 + 1 + 2 = 3 pairs; all-pairs over 6 would be 15.
        assertThat(dedup.dedupe(postings).pairsCompared()).isEqualTo(6);
    }

    @Test
    void thresholdMustBeAProbability() {
        assertThatThrownBy(() -> new Deduplicator(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Deduplicator(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(dedup.threshold()).isEqualTo(0.8);
    }

    private DedupCandidate c(String company, String title, String location, String department) {
        long id = ids.incrementAndGet();
        return new DedupCandidate(id, new PostingKey(Ats.GREENHOUSE, Long.toString(id)), company, title,
                List.of(location), department, "https://example.com/jobs/" + id);
    }
}
