package io.github.jozephzemambo.jobradar.source.ashby;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Wire format of {@code GET api.ashbyhq.com/posting-api/job-board/{board}?includeCompensation=true}. */
final class AshbyDtos {

    private AshbyDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Board(String apiVersion, List<Job> jobs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Job(
            String id,
            String title,
            String department,
            String team,
            String location,
            List<SecondaryLocation> secondaryLocations,
            Boolean isRemote,
            String workplaceType,
            String publishedAt,
            Boolean isListed,
            String jobUrl,
            String descriptionPlain,
            String descriptionHtml,
            Compensation compensation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SecondaryLocation(String location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Compensation(String compensationTierSummary) {
    }
}
