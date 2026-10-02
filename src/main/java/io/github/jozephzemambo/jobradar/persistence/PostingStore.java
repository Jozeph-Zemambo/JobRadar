package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import io.github.jozephzemambo.jobradar.source.BoardSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one board's current listing to the database: insert new postings, refresh ones still listed, close
 * ones that disappeared, reopen ones that came back.
 *
 * <p>Only call this for boards that were fetched <em>successfully</em>. A failed fetch says nothing about whether
 * postings closed, so the caller must skip it rather than pass an empty list. For the same reason, a fetch that
 * may have missed postings is passed as an incomplete {@link BoardSnapshot}, which never closes anything.
 */
@Service
public class PostingStore {

    private final PostingRepository repository;

    public PostingStore(PostingRepository repository) {
        this.repository = repository;
    }

    /**
     * One transaction per board: a database error on one board doesn't roll back the others.
     *
     * @param scorer scores the <em>stored</em> state after merging (so a description kept from an earlier run
     *               still counts); may return null to leave a posting unscored
     */
    @Transactional
    public SyncCounts syncBoard(Company company, List<Posting> current, Instant now,
            Function<Posting, ScoreBreakdown> scorer) {
        return syncBoard(company, BoardSnapshot.complete(current), now, scorer);
    }

    /**
     * As above, for a snapshot that may be incomplete: postings in it are inserted or refreshed either way, but
     * stored postings missing from an incomplete snapshot are left open, since their absence proves nothing.
     */
    @Transactional
    public SyncCounts syncBoard(Company company, BoardSnapshot snapshot, Instant now,
            Function<Posting, ScoreBreakdown> scorer) {
        List<Posting> current = snapshot.postings();
        Map<String, PostingEntity> existing = new HashMap<>();
        for (PostingEntity entity : repository.findByAtsAndBoardToken(company.ats(), company.boardToken())) {
            existing.put(entity.getExternalId(), entity);
        }

        int created = 0;
        int updated = 0;
        int reopened = 0;
        Set<String> seen = new HashSet<>();
        List<PostingEntity> toSave = new ArrayList<>(current.size());
        for (Posting posting : current) {
            if (!seen.add(posting.externalId())) {
                continue; // the same id twice in one response: keep the first
            }
            PostingEntity entity = existing.get(posting.externalId());
            if (entity == null) {
                entity = PostingEntity.create(posting, company.boardToken(), now);
                created++;
            } else if (entity.refresh(posting, now)) {
                reopened++;
            } else {
                updated++;
            }
            entity.applyScore(scorer.apply(entity.toPosting()));
            toSave.add(entity);
        }

        int closed = 0;
        for (PostingEntity entity : existing.values()) {
            if (snapshot.complete() && !seen.contains(entity.getExternalId()) && entity.isOpen()) {
                entity.close(now);
                toSave.add(entity);
                closed++;
            }
        }
        repository.saveAll(toSave);
        return new SyncCounts(created, updated, reopened, closed);
    }
}
