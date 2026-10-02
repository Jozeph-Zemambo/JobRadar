package io.github.jozephzemambo.jobradar.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DomainTest {

    @Test
    void postingDefaultsAndDefensiveCopy() {
        List<String> locations = new ArrayList<>(List.of("London"));
        Posting p = new Posting(Ats.LEVER, "id-1", "Spotify", "  Android Engineer ", locations, null, null,
                "https://jobs.lever.co/spotify/id-1", null, null, null, null);

        locations.add("Stockholm");

        assertThat(p.title()).isEqualTo("Android Engineer");
        assertThat(p.locations()).containsExactly("London");
        assertThat(p.workplaceType()).isEqualTo(WorkplaceType.UNKNOWN);
        assertThat(p.canonicalUrl()).isEqualTo(p.url());
        assertThat(p.descriptionText()).isEmpty();
        assertThat(p.key()).isEqualTo(new PostingKey(Ats.LEVER, "id-1"));
    }

    @Test
    void postingRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new Posting(Ats.LEVER, " ", "c", "t", null, null, null, "u", null, null, null, null))
                .hasMessageContaining("externalId");
        assertThatThrownBy(() -> new Posting(null, "1", "c", "t", null, null, null, "u", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void companyDefaultsNameToBoardToken() {
        assertThat(new Company(null, Ats.ASHBY, "ramp").name()).isEqualTo("ramp");
        assertThatThrownBy(() -> new Company("Ramp", Ats.ASHBY, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            onsite, ONSITE
            On-site, ONSITE
            Hybrid, HYBRID
            remote, REMOTE
            Remote, REMOTE
            flexible, UNKNOWN
            """)
    void workplaceTypeFromRaw(String raw, WorkplaceType expected) {
        assertThat(WorkplaceType.fromRaw(raw)).isEqualTo(expected);
    }

    @Test
    void workplaceTypeNullIsUnknown() {
        assertThat(WorkplaceType.fromRaw(null)).isEqualTo(WorkplaceType.UNKNOWN);
    }
}
