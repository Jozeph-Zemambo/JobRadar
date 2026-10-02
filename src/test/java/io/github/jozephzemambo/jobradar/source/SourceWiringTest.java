package io.github.jozephzemambo.jobradar.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.domain.Ats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Spring collects every JobSource bean into the registry and binds the curated company list. */
@SpringBootTest
class SourceWiringTest {

    @Autowired
    SourceRegistry registry;

    @Autowired
    JobRadarProperties properties;

    @Test
    void everyAtsHasASourceAndEveryCompanyIsSupported() {
        for (Ats ats : Ats.values()) {
            assertThat(registry.forAts(ats).ats()).isEqualTo(ats);
        }
        assertThat(properties.companies()).hasSize(51)
                .allSatisfy(c -> assertThat(registry.supports(c.ats())).isTrue());
        assertThat(properties.sources().leverBaseUrl()).isEqualTo("https://api.lever.co");
        assertThat(properties.http().maxAttempts()).isEqualTo(4);
    }
}
