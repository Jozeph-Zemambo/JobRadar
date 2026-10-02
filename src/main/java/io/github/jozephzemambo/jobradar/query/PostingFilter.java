package io.github.jozephzemambo.jobradar.query;

import io.github.jozephzemambo.jobradar.domain.Ats;
import java.time.Instant;

/**
 * Optional filters for listing postings. Null means "don't filter on this".
 *
 * @param minScore          minimum score, inclusive
 * @param since             first seen at or after this instant
 * @param company           company name, case-insensitive exact match
 * @param ats               source ATS
 * @param status            open, closed, or all
 * @param titleQuery        substring of the normalized title
 * @param includeDuplicates whether postings linked as duplicates are listed
 */
public record PostingFilter(Double minScore, Instant since, String company, Ats ats, PostingStatus status,
        String titleQuery, boolean includeDuplicates) {

    public PostingFilter {
        status = status == null ? PostingStatus.OPEN : status;
        if (minScore != null && (!Double.isFinite(minScore) || minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
    }

    public enum PostingStatus {
        OPEN,
        CLOSED,
        ALL
    }
}
