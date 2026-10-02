package io.github.jozephzemambo.jobradar.query;

import io.github.jozephzemambo.jobradar.dedup.DedupResult;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.persistence.PostingEntity;
import java.time.Instant;
import java.util.List;

/** Read models returned by the API. Entities never leave the service layer. */
public final class PostingViews {

    private PostingViews() {
    }

    /** One row in a ranked list. */
    public record Summary(long id, Ats ats, String company, String title, List<String> locations,
            WorkplaceType workplaceType, String department, String url, Double score, List<String> matchedSkills,
            Instant firstSeenAt, Instant closedAt, Long duplicateOfId) {

        public static Summary of(PostingEntity e) {
            return new Summary(e.getId(), e.getAts(), e.getCompany(), e.getTitle(), e.getLocations(),
                    e.getWorkplaceType(), e.getDepartment(), e.getUrl(), e.getScore(), e.getMatchedSkills(),
                    e.getFirstSeenAt(), e.getClosedAt(), e.getDuplicateOfId());
        }
    }

    /** Everything about one posting, including why it scored as it did and what duplicates it. */
    public record Detail(Summary summary, String description, String compensation, Instant sourcePublishedAt,
            Instant lastSeenAt, List<String> skills, List<String> missingSkills, DedupResult.Reason dedupReason,
            List<Long> duplicateIds) {
    }

    /** A page of results; a stable JSON shape instead of serializing Spring's {@code Page}. */
    public record PageOf<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }

    /** One configured board and what JobRadar has stored for it. */
    public record Board(String company, Ats ats, String boardToken, long postingsSeen, long openPostings,
            Instant lastSeenAt) {
    }
}
