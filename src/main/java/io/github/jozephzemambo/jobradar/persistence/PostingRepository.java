package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.dedup.DedupResult;
import io.github.jozephzemambo.jobradar.domain.Ats;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostingRepository extends JpaRepository<PostingEntity, Long>,
        JpaSpecificationExecutor<PostingEntity> {

    /** Every posting ever seen on one board, open or closed. One query per board per ingest. */
    List<PostingEntity> findByAtsAndBoardToken(Ats ats, String boardToken);

    /**
     * Just the columns dedup needs, for every open posting. Selecting fields instead of entities avoids loading
     * descriptions (the bulk of each row) and avoids putting thousands of entities in the persistence context.
     */
    @Query("""
            select p.id, p.ats, p.externalId, p.company, p.title, p.locations, p.department, p.canonicalUrl
            from PostingEntity p where p.closedAt is null""")
    List<Object[]> findOpenDedupFields();

    @Modifying
    @Query("update PostingEntity p set p.duplicateOfId = null, p.dedupReason = null where p.duplicateOfId is not null")
    int clearDuplicateLinks();

    @Modifying
    @Query("update PostingEntity p set p.duplicateOfId = :canonicalId, p.dedupReason = :reason where p.id = :id")
    int markDuplicate(@Param("id") long id, @Param("canonicalId") long canonicalId,
            @Param("reason") DedupResult.Reason reason);

    List<PostingEntity> findByDuplicateOfIdOrderByIdAsc(Long canonicalId);

    /** Per board: total postings, open postings, last time any posting was seen. */
    @Query("""
            select p.ats, p.boardToken, count(p), sum(case when p.closedAt is null then 1 else 0 end), max(p.lastSeenAt)
            from PostingEntity p group by p.ats, p.boardToken""")
    List<Object[]> boardSummaries();

    long countByClosedAtIsNull();

    long countByClosedAtIsNotNull();

    long countByClosedAtIsNullAndDuplicateOfIdIsNotNull();

    @Query("""
            select p.ats, count(p) from PostingEntity p
            where p.closedAt is null and p.duplicateOfId is null group by p.ats order by count(p) desc""")
    List<Object[]> openCountsByAts();

    @Query("""
            select p.workplaceType, count(p) from PostingEntity p
            where p.closedAt is null and p.duplicateOfId is null group by p.workplaceType order by count(p) desc""")
    List<Object[]> openCountsByWorkplace();

    @Query("""
            select p.company, count(p) from PostingEntity p
            where p.closedAt is null and p.duplicateOfId is null group by p.company order by count(p) desc, p.company""")
    List<Object[]> openCountsByCompany(Pageable limit);

    /** The detected-skills list of every open, non-duplicate posting (converted back to a List per row). */
    @Query("select p.skills from PostingEntity p where p.closedAt is null and p.duplicateOfId is null")
    List<Object> openSkillLists();

    /**
     * First-seen and closed times of closed postings whose opening JobRadar actually observed: first seen after
     * their own board's first sync. A board's first sync records its backlog, whose real start dates are unknown;
     * a board added months after tracking began would otherwise bring its whole backlog in as "new".
     */
    @Query("""
            select p.firstSeenAt, p.closedAt from PostingEntity p
            where p.closedAt is not null and p.firstSeenAt > (
                select min(q.firstSeenAt) from PostingEntity q where q.ats = p.ats and q.boardToken = p.boardToken)""")
    List<Object[]> closedLifetimesOfObservedOpenings();

    /** Postings first seen after {@code since} that were genuinely new (not part of their board's first sync). */
    @Query("""
            select count(p) from PostingEntity p
            where p.firstSeenAt > :since and p.firstSeenAt > (
                select min(q.firstSeenAt) from PostingEntity q where q.ats = p.ats and q.boardToken = p.boardToken)""")
    long countObservedOpeningsSince(@Param("since") Instant since);
}
