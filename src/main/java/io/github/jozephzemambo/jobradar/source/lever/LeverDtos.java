package io.github.jozephzemambo.jobradar.source.lever;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire format of {@code GET api.lever.co/v0/postings/{board}?mode=json}. The response is a bare JSON array.
 * Lever has no {@code updatedAt}, and {@code createdAt} (epoch millis) can be a decade old on evergreen reqs.
 */
final class LeverDtos {

    private LeverDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Posting(
            String id,
            String text,
            String hostedUrl,
            Long createdAt,
            Categories categories,
            String workplaceType,
            String descriptionPlain,
            List<ListSection> lists,
            String additionalPlain,
            SalaryRange salaryRange) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Categories(String commitment, String department, String location, String team, List<String> allLocations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListSection(String text, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SalaryRange(String currency, String interval, Double min, Double max) {
    }
}
