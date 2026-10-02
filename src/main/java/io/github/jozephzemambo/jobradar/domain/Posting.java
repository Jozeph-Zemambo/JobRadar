package io.github.jozephzemambo.jobradar.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One job posting, normalized from whichever ATS it came from.
 * Immutable: every source maps its own DTO into this shape and nothing downstream mutates it.
 *
 * @param ats                 source ATS
 * @param externalId          the ATS's id for the posting, unique within the ATS (Workday ids are prefixed with
 *                            the board token, since Workday paths repeat across career sites)
 * @param company             company display name
 * @param title               title as published (trimmed)
 * @param locations           every location the posting lists, primary first
 * @param workplaceType       onsite / hybrid / remote
 * @param department          department or team, if the ATS exposes one
 * @param url                 public posting URL as published
 * @param canonicalUrl        {@link #url} with tracking noise removed, used for exact dedup
 * @param descriptionText     plain-text description (HTML stripped)
 * @param sourcePublishedAt   when the ATS says it was published; may be null and is not trusted for age
 * @param compensationSummary free-text pay range if the ATS publishes one, else null
 */
public record Posting(
        Ats ats,
        String externalId,
        String company,
        String title,
        List<String> locations,
        WorkplaceType workplaceType,
        String department,
        String url,
        String canonicalUrl,
        String descriptionText,
        Instant sourcePublishedAt,
        String compensationSummary) {

    public Posting {
        Objects.requireNonNull(ats, "ats");
        requireText(externalId, "externalId");
        requireText(company, "company");
        requireText(title, "title");
        requireText(url, "url");
        title = title.strip();
        locations = locations == null ? List.of() : List.copyOf(locations);
        workplaceType = workplaceType == null ? WorkplaceType.UNKNOWN : workplaceType;
        canonicalUrl = canonicalUrl == null ? url : canonicalUrl;
        descriptionText = descriptionText == null ? "" : descriptionText;
    }

    /** Identity of the posting within JobRadar: the same ATS id is the same posting. */
    public PostingKey key() {
        return new PostingKey(ats, externalId);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
