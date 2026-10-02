package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import java.time.Instant;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable query pieces for {@code GET /api/postings}. Each optional filter is its own {@link Specification}, so
 * the controller combines only the ones the caller supplied instead of one query method per combination.
 */
public final class PostingSpecifications {

    private PostingSpecifications() {
    }

    public static Specification<PostingEntity> isOpen() {
        return (root, query, cb) -> cb.isNull(root.get("closedAt"));
    }

    public static Specification<PostingEntity> isClosed() {
        return (root, query, cb) -> cb.isNotNull(root.get("closedAt"));
    }

    public static Specification<PostingEntity> notDuplicate() {
        return (root, query, cb) -> cb.isNull(root.get("duplicateOfId"));
    }

    public static Specification<PostingEntity> minScore(double minScore) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("score"), minScore);
    }

    /** First seen by JobRadar at or after {@code since} (not the ATS's own date, which can be years stale). */
    public static Specification<PostingEntity> firstSeenSince(Instant since) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("firstSeenAt"), since);
    }

    public static Specification<PostingEntity> company(String company) {
        String lower = company.strip().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get("company")), lower);
    }

    public static Specification<PostingEntity> ats(Ats ats) {
        return (root, query, cb) -> cb.equal(root.get("ats"), ats);
    }

    /** Title search on the normalized title, so "Sr. Eng" finds "Senior Engineer". */
    public static Specification<PostingEntity> titleContains(String text) {
        String pattern = "%" + escapeLike(TitleNormalizer.normalize(text)) + "%";
        return (root, query, cb) -> cb.like(root.get("normalizedTitle"), pattern, '\\');
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
