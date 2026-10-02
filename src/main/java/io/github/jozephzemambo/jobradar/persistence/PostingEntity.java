package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.dedup.DedupResult;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.normalize.TitleNormalizer;
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent form of a {@link Posting}, plus what only JobRadar knows: when it was first and last seen, and
 * when it disappeared.
 *
 * <p>Kept separate from the {@code Posting} record on purpose: JPA needs a mutable class with a no-arg
 * constructor, while the domain model is immutable. Mapping happens in exactly two places,
 * {@link #create} and {@link #refresh}.
 */
@Entity
@Table(name = "posting")
public class PostingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "posting_seq")
    @SequenceGenerator(name = "posting_seq", sequenceName = "posting_seq", allocationSize = 50)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Ats ats;

    @Column(name = "external_id", nullable = false, length = 400)
    private String externalId;

    @Column(name = "board_token", nullable = false, length = 100)
    private String boardToken;

    @Column(nullable = false, length = 200)
    private String company;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "normalized_title", nullable = false, length = 500)
    private String normalizedTitle;

    @Convert(converter = StringListConverter.class)
    @Column(length = 4000)
    private List<String> locations = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "workplace_type", nullable = false, length = 20)
    private WorkplaceType workplaceType;

    @Column(length = 500)
    private String department;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(name = "canonical_url", nullable = false, length = 2000)
    private String canonicalUrl;

    @Column(length = 200000)
    private String description;

    @Column(length = 500)
    private String compensation;

    @Column(name = "source_published_at")
    private Instant sourcePublishedAt;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Id of the posting this one duplicates; a plain column, not an association, so nothing loads eagerly. */
    @Column(name = "duplicate_of_id")
    private Long duplicateOfId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dedup_reason", length = 20)
    private DedupResult.Reason dedupReason;

    private Double score;

    @Convert(converter = StringListConverter.class)
    @Column(length = 2000)
    private List<String> skills = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "matched_skills", length = 2000)
    private List<String> matchedSkills = new ArrayList<>();

    protected PostingEntity() {
        // for JPA
    }

    /** A posting seen for the first time. */
    public static PostingEntity create(Posting posting, String boardToken, Instant now) {
        PostingEntity entity = new PostingEntity();
        entity.ats = posting.ats();
        entity.externalId = truncate(posting.externalId(), 400);
        entity.boardToken = boardToken;
        entity.firstSeenAt = now;
        entity.refresh(posting, now);
        return entity;
    }

    /**
     * The posting was seen again: take the latest content, bump {@code lastSeenAt}, and reopen it if it had been
     * marked closed (boards sometimes unpublish and republish the same req).
     *
     * <p>Paged sources (Workday, SmartRecruiters) only make detail calls for the first N postings per run, so a
     * posting described on an earlier run can come back with list fields only. Fields the new observation lacks
     * (no description, no locations, unknown workplace, no dates) keep their stored values rather than being
     * erased; a partial observation never overwrites a richer one.
     *
     * @return true if the posting was closed and is now reopened
     */
    public boolean refresh(Posting posting, Instant now) {
        company = posting.company();
        title = truncate(posting.title(), 500);
        normalizedTitle = truncate(TitleNormalizer.normalize(posting.title()), 500);
        if (!posting.locations().isEmpty() || locations == null) {
            locations = new ArrayList<>(posting.locations());
        }
        if (posting.workplaceType() != WorkplaceType.UNKNOWN || workplaceType == null) {
            workplaceType = posting.workplaceType();
        }
        department = keepIfMissing(truncate(posting.department(), 500), department);
        url = truncate(posting.url(), 2000);
        canonicalUrl = truncate(posting.canonicalUrl(), 2000);
        if (!posting.descriptionText().isBlank() || description == null) {
            description = truncate(posting.descriptionText(), 200000);
        }
        compensation = keepIfMissing(truncate(posting.compensationSummary(), 500), compensation);
        sourcePublishedAt = keepIfMissing(posting.sourcePublishedAt(), sourcePublishedAt);
        lastSeenAt = now;
        boolean reopened = closedAt != null;
        closedAt = null;
        return reopened;
    }

    /** Stores the ranking; a null breakdown clears it. */
    public void applyScore(ScoreBreakdown breakdown) {
        if (breakdown == null) {
            score = null;
            skills = new ArrayList<>();
            matchedSkills = new ArrayList<>();
        } else {
            score = breakdown.score();
            skills = new ArrayList<>(breakdown.postingSkills());
            matchedSkills = new ArrayList<>(breakdown.matchedSkills());
        }
    }

    /** The posting is no longer on its board. */
    public void close(Instant now) {
        if (closedAt == null) {
            closedAt = now;
        }
    }

    public Posting toPosting() {
        return new Posting(ats, externalId, company, title, locations, workplaceType, department, url, canonicalUrl,
                description, sourcePublishedAt, compensation);
    }

    private static <T> T keepIfMissing(T observed, T stored) {
        return observed != null ? observed : stored;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Ats getAts() {
        return ats;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getBoardToken() {
        return boardToken;
    }

    public String getCompany() {
        return company;
    }

    public String getTitle() {
        return title;
    }

    public String getNormalizedTitle() {
        return normalizedTitle;
    }

    public List<String> getLocations() {
        return List.copyOf(locations);
    }

    public WorkplaceType getWorkplaceType() {
        return workplaceType;
    }

    public String getDepartment() {
        return department;
    }

    public String getUrl() {
        return url;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public String getDescription() {
        return description;
    }

    public String getCompensation() {
        return compensation;
    }

    public Instant getSourcePublishedAt() {
        return sourcePublishedAt;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Double getScore() {
        return score;
    }

    public List<String> getSkills() {
        return List.copyOf(skills);
    }

    public List<String> getMatchedSkills() {
        return List.copyOf(matchedSkills);
    }

    public Long getDuplicateOfId() {
        return duplicateOfId;
    }

    public DedupResult.Reason getDedupReason() {
        return dedupReason;
    }

    public boolean isOpen() {
        return closedAt == null;
    }

    /**
     * Identity is the database id. Two transient (unsaved) entities are never equal, and the hash code is
     * constant per class so an entity doesn't change buckets in a HashSet when it gets its id on persist.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PostingEntity other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return PostingEntity.class.hashCode();
    }
}
