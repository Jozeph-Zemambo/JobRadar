package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.dedup.DedupResult;
import io.github.jozephzemambo.jobradar.domain.Ats;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostingRepository extends JpaRepository<PostingEntity, Long> {

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
}
