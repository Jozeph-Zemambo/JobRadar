package io.github.jozephzemambo.jobradar.stats;

import io.github.jozephzemambo.jobradar.persistence.PostingEntity;
import io.github.jozephzemambo.jobradar.persistence.PostingRepository;
import io.github.jozephzemambo.jobradar.persistence.PostingSpecifications;
import io.github.jozephzemambo.jobradar.query.PostingViews;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Streams open, non-duplicate postings as newline-delimited JSON for downstream tools.
 *
 * <p>Reads in pages of {@link #BATCH} ordered by id instead of holding one long transaction or loading
 * everything into memory, so export size is bounded by the output stream, not the heap.
 */
@Service
public class ExportService {

    static final int BATCH = 500;

    /** One exported line: the summary plus the plain-text description. */
    public record ExportedPosting(PostingViews.Summary posting, String description, String compensation) {
    }

    private final PostingRepository repository;
    private final ObjectMapper mapper;

    public ExportService(PostingRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** @return number of postings written */
    public long export(Double minScore, Instant since, OutputStream out) throws IOException {
        Specification<PostingEntity> spec = Specification.allOf(List.of(
                PostingSpecifications.isOpen(), PostingSpecifications.notDuplicate()));
        if (minScore != null) {
            spec = spec.and(PostingSpecifications.minScore(minScore));
        }
        if (since != null) {
            spec = spec.and(PostingSpecifications.firstSeenSince(since));
        }
        long written = 0;
        int page = 0;
        Page<PostingEntity> batch;
        do {
            batch = repository.findAll(spec, PageRequest.of(page++, BATCH, Sort.by("id")));
            for (PostingEntity e : batch) {
                ExportedPosting line = new ExportedPosting(PostingViews.Summary.of(e), e.getDescription(),
                        e.getCompensation());
                out.write(mapper.writeValueAsString(line).getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                written++;
            }
            out.flush();
        } while (batch.hasNext());
        return written;
    }
}
