package io.github.jozephzemambo.jobradar.source.greenhouse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Wire format of {@code GET boards-api.greenhouse.io/v1/boards/{board}/jobs?content=true}.
 * Only the fields JobRadar uses are declared; unknown fields (Greenhouse keeps adding them, e.g.
 * {@code ai_disclaimer}, {@code data_compliance}) are ignored so new fields can't break ingestion.
 */
final class GreenhouseDtos {

    private GreenhouseDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Board(List<Job> jobs, Meta meta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(Integer total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Job(
            long id,
            String title,
            @JsonProperty("absolute_url") String absoluteUrl,
            Location location,
            List<Named> departments,
            @JsonProperty("first_published") String firstPublished,
            @JsonProperty("updated_at") String updatedAt,
            String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Location(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Named(String name) {
    }
}
