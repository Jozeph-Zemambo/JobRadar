package io.github.jozephzemambo.jobradar.source.workday;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Wire format of Workday's public candidate-experience ("CXS") endpoints:
 * <ul>
 *   <li>{@code POST /wday/cxs/{tenant}/{site}/jobs} with {@code {"limit":20,"offset":N,...}}: a page of postings
 *   (max 20). {@code total} is only populated on the first page.</li>
 *   <li>{@code GET /wday/cxs/{tenant}/{site}{externalPath}}: one posting with its HTML description.</li>
 * </ul>
 */
final class WorkdayDtos {

    private WorkdayDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(Integer total, List<ListItem> jobPostings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ListItem(String title, String externalPath, String locationsText, String postedOn,
            List<String> bulletFields) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Detail(Info jobPostingInfo, Organization hiringOrganization) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Info(String id, String title, String jobDescription, String location, List<String> additionalLocations,
            String startDate, String timeType, String jobReqId, String remoteType, String externalUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Organization(String name) {
    }
}
