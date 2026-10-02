package io.github.jozephzemambo.jobradar.source.smartrecruiters;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire format of SmartRecruiters' public Posting API:
 * <ul>
 *   <li>{@code GET /v1/companies/{company}/postings?limit=100&offset=N}: a page of postings, no description.</li>
 *   <li>{@code GET /v1/companies/{company}/postings/{id}}: one posting with HTML sections.</li>
 * </ul>
 * An unknown company returns 200 with {@code totalFound: 0}, not 404.
 */
final class SmartRecruitersDtos {

    private SmartRecruitersDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(Integer offset, Integer limit, Integer totalFound, List<ListItem> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListItem(String id, String name, String refNumber, String releasedDate, Location location, Label department,
            Label function) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Location(String city, String region, String country, Boolean remote, Boolean hybrid, String fullLocation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Label(String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Detail(String postingUrl, JobAd jobAd) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JobAd(Sections sections) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sections(Section companyDescription, Section jobDescription, Section qualifications,
            Section additionalInformation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Section(String title, String text) {
    }
}
