package io.github.jozephzemambo.jobradar.config;

import io.github.jozephzemambo.jobradar.scoring.KeywordScorer;
import io.github.jozephzemambo.jobradar.scoring.Profile;
import io.github.jozephzemambo.jobradar.scoring.Scorer;
import io.github.jozephzemambo.jobradar.scoring.SkillDictionary;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Scoring wiring. The {@link Scorer} implementation is picked by {@code jobradar.scorer} (default
 * {@code keyword}); a second implementation would add its own {@code @ConditionalOnProperty} bean here and no
 * caller would change.
 */
@Configuration
public class ScoringConfig {

    @Bean
    SkillDictionary skillDictionary(JobRadarProperties props) {
        return new SkillDictionary(props.skills());
    }

    /** The configured profile, with skill names checked against the dictionary at startup. */
    @Bean
    Profile profile(JobRadarProperties props, SkillDictionary dictionary) {
        Profile profile = props.profile();
        List<String> unknown = profile.skills().stream().filter(s -> dictionary.canonicalName(s) == null).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Profile lists skills missing from skills.yml: " + unknown);
        }
        return profile;
    }

    @Bean
    @ConditionalOnProperty(name = "jobradar.scorer", havingValue = "keyword", matchIfMissing = true)
    Scorer keywordScorer(SkillDictionary dictionary) {
        return new KeywordScorer(dictionary);
    }
}
