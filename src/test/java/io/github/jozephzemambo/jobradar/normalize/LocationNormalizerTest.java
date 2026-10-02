package io.github.jozephzemambo.jobradar.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LocationNormalizerTest {

    @ParameterizedTest(name = "{0} ~ {1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            New York, NY (HQ)                     | New York City              | true
            NYC                                   | New York, NY               | true
            London, United Kingdom                | London                     | true
            Seattle, San Francisco, New York City | San Francisco, CA          | true
            US-Remote                             | Remote (US)                | true
            Seoul, South Korea                    | Copenhagen, Denmark        | false
            Chicago, IL                           | New York, NY               | false
            Remote (US)                           | Remote (Canada)            | true
            Dublin                                | US-Remote                  | false
            Mountain View, California             | San Francisco, California  | false
            Hybrid                                | Hybrid                     | false
            Petaling Jaya, Selangor, Malaysia     | Petaling Jaya, Malaysia    | true
            Shah Alam, Selangor, Malaysia         | Petaling Jaya, Selangor, Malaysia | false
            US, Oregon, Hillsboro                 | Hillsboro, Oregon          | true
            Shenzhen, Guangdong Province, China   | shenzhen, Guangdong Province, China | true
            Toronto, ON, ca                       | Toronto                    | true
            On-site                               | On-site                    | false
            On site - Austin, TX                  | Austin, Texas              | true
            In-Office                             | In office                  | false
            """)
    void overlap(String a, String b, boolean expected) {
        assertThat(LocationNormalizer.overlaps(List.of(a), List.of(b))).isEqualTo(expected);
    }

    @Test
    void cityKeysWinOverGenericOnes() {
        assertThat(LocationNormalizer.keys(List.of("New York, NY (HQ)", "Remote (US)")))
                .containsExactly("new york");
    }

    @Test
    void hyphenatedWorkplaceWordsAreNotPlaces() {
        assertThat(LocationNormalizer.keys(List.of("On-site"))).isEmpty();
        assertThat(LocationNormalizer.keys(List.of("On-site - Austin, TX"))).containsExactly("austin");
    }

    @Test
    void genericKeysUsedWhenNoCity() {
        assertThat(LocationNormalizer.keys(List.of("US-Remote"))).containsExactlyInAnyOrder("us", "remote");
    }

    @Test
    void missingLocationsNeverOverlap() {
        assertThat(LocationNormalizer.overlaps(List.of(), null)).isFalse();
        assertThat(LocationNormalizer.overlaps(List.of(), List.of("London"))).isFalse();
    }
}
