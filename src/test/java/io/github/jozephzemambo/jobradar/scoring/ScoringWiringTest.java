package io.github.jozephzemambo.jobradar.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Posting;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The shipped skills.yml and example profile load, agree with each other, and produce a sensible ranking. */
@SpringBootTest
class ScoringWiringTest {

    @Autowired
    Scorer scorer;

    @Autowired
    Profile profile;

    @Autowired
    SkillDictionary dictionary;

    @Test
    void shippedConfigurationRanksABackendRoleAboveASalesRole() {
        assertThat(scorer).isInstanceOf(KeywordScorer.class);
        assertThat(profile.name()).isEqualTo("generalist-swe");
        assertThat(dictionary.size()).isGreaterThan(50);

        Posting backend = new Posting(Ats.LEVER, "1", "Co", "Backend Software Engineer", List.of(), null, null,
                "https://x.io/1", null, "Build services in Java and Spring on AWS with PostgreSQL and Docker.",
                null, null);
        Posting sales = new Posting(Ats.LEVER, "2", "Co", "Account Executive", List.of(), null, null,
                "https://x.io/2", null, "Own our go-to-market motion and grow revenue.", null, null);

        ScoreBreakdown backendScore = scorer.score(backend, profile);
        ScoreBreakdown salesScore = scorer.score(sales, profile);

        assertThat(backendScore.score()).isGreaterThan(0.8);
        assertThat(salesScore.score()).isZero();
        assertThat(salesScore.postingSkills()).doesNotContain("Go");
    }
}
