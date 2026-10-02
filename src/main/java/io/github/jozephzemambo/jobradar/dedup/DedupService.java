package io.github.jozephzemambo.jobradar.dedup;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.PostingKey;
import io.github.jozephzemambo.jobradar.persistence.PostingRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes duplicate links over every open posting. Running over all open postings (rather than only the
 * boards in this ingest) is what lets the exact-URL pass catch the same posting on two different boards.
 */
@Service
public class DedupService {

    private final Deduplicator deduplicator;
    private final PostingRepository repository;

    public DedupService(Deduplicator deduplicator, PostingRepository repository) {
        this.deduplicator = deduplicator;
        this.repository = repository;
    }

    @Transactional
    public DedupResult refresh() {
        List<DedupCandidate> candidates = loadOpenCandidates();
        DedupResult result = deduplicator.dedupe(candidates);
        repository.clearDuplicateLinks();
        for (DedupResult.Duplicate duplicate : result.duplicates()) {
            repository.markDuplicate(duplicate.duplicate().id(), duplicate.canonical().id(), duplicate.reason());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<DedupCandidate> loadOpenCandidates() {
        List<DedupCandidate> candidates = new ArrayList<>();
        for (Object[] row : repository.findOpenDedupFields()) {
            @SuppressWarnings("unchecked")
            List<String> locations = (List<String>) row[5];
            candidates.add(new DedupCandidate((Long) row[0], new PostingKey((Ats) row[1], (String) row[2]),
                    (String) row[3], (String) row[4], locations, (String) row[6], (String) row[7]));
        }
        return candidates;
    }
}
