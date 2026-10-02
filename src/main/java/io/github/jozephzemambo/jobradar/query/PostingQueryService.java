package io.github.jozephzemambo.jobradar.query;

import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.ats;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.company;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.firstSeenSince;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.isClosed;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.isOpen;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.minScore;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.notDuplicate;
import static io.github.jozephzemambo.jobradar.persistence.PostingSpecifications.titleContains;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.persistence.PostingEntity;
import io.github.jozephzemambo.jobradar.persistence.PostingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the API: filtering, ranking, and per-posting detail. */
@Service
@Transactional(readOnly = true)
public class PostingQueryService {

    /** How results are ordered. */
    public enum SortBy {
        /** Highest score first; unscored postings last. */
        SCORE,
        /** Most recently first-seen first. */
        NEWEST
    }

    public static final int MAX_PAGE_SIZE = 200;

    private final PostingRepository repository;
    private final List<Company> companies;

    public PostingQueryService(PostingRepository repository, JobRadarProperties props) {
        this.repository = repository;
        this.companies = props.companies();
    }

    public PostingViews.PageOf<PostingViews.Summary> search(PostingFilter filter, SortBy sortBy, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        Page<PostingEntity> result = repository.findAll(toSpecification(filter),
                PageRequest.of(page, size, sort(sortBy)));
        return new PostingViews.PageOf<>(result.map(PostingViews.Summary::of).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public PostingViews.Detail detail(long id) {
        PostingEntity e = repository.findById(id).orElseThrow(() -> new NoSuchElementException("No posting " + id));
        List<String> missing = new ArrayList<>(e.getSkills());
        missing.removeAll(e.getMatchedSkills());
        List<Long> duplicates = repository.findByDuplicateOfIdOrderByIdAsc(id).stream().map(PostingEntity::getId).toList();
        return new PostingViews.Detail(PostingViews.Summary.of(e), e.getDescription(), e.getCompensation(),
                e.getSourcePublishedAt(), e.getLastSeenAt(), e.getSkills(), missing, e.getDedupReason(), duplicates);
    }

    /** Every configured board, joined with what has been stored for it (zeros if never fetched). */
    public List<PostingViews.Board> boards() {
        Map<String, Object[]> byBoard = new HashMap<>();
        for (Object[] row : repository.boardSummaries()) {
            byBoard.put(row[0] + "/" + row[1], row);
        }
        List<PostingViews.Board> boards = new ArrayList<>(companies.size());
        for (Company c : companies) {
            Object[] row = byBoard.get(c.ats() + "/" + c.boardToken());
            boards.add(row == null
                    ? new PostingViews.Board(c.name(), c.ats(), c.boardToken(), 0, 0, null)
                    : new PostingViews.Board(c.name(), c.ats(), c.boardToken(), ((Number) row[2]).longValue(),
                            ((Number) row[3]).longValue(), (Instant) row[4]));
        }
        return boards;
    }

    static Specification<PostingEntity> toSpecification(PostingFilter f) {
        List<Specification<PostingEntity>> parts = new ArrayList<>();
        switch (f.status()) {
            case OPEN -> parts.add(isOpen());
            case CLOSED -> parts.add(isClosed());
            case ALL -> { }
        }
        if (!f.includeDuplicates()) {
            parts.add(notDuplicate());
        }
        if (f.minScore() != null) {
            parts.add(minScore(f.minScore()));
        }
        if (f.since() != null) {
            parts.add(firstSeenSince(f.since()));
        }
        if (f.company() != null && !f.company().isBlank()) {
            parts.add(company(f.company()));
        }
        Ats ats = f.ats();
        if (ats != null) {
            parts.add(ats(ats));
        }
        if (f.titleQuery() != null && !f.titleQuery().isBlank()) {
            parts.add(titleContains(f.titleQuery()));
        }
        return Specification.allOf(parts);
    }

    /** Highest score first with unscored postings last, then newest; {@code id} makes paging deterministic. */
    private static Sort sort(SortBy sortBy) {
        return switch (sortBy == null ? SortBy.SCORE : sortBy) {
            case SCORE -> Sort.by(Sort.Order.desc("score").nullsLast(), Sort.Order.desc("firstSeenAt"),
                    Sort.Order.asc("id"));
            case NEWEST -> Sort.by(Sort.Order.desc("firstSeenAt"), Sort.Order.asc("id"));
        };
    }
}
