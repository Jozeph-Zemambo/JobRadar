package io.github.jozephzemambo.jobradar.api;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.query.PostingFilter;
import io.github.jozephzemambo.jobradar.query.PostingQueryService;
import io.github.jozephzemambo.jobradar.query.PostingViews;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PostingController {

    private final PostingQueryService queries;

    public PostingController(PostingQueryService queries) {
        this.queries = queries;
    }

    /**
     * Ranked, filtered postings.
     *
     * @param since an ISO date ({@code 2026-10-01}) or instant ({@code 2026-10-01T12:00:00Z}), compared with
     *              when JobRadar first saw the posting
     */
    @GetMapping("/postings")
    public PostingViews.PageOf<PostingViews.Summary> postings(
            @RequestParam(required = false) Double minScore,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) Ats ats,
            @RequestParam(defaultValue = "OPEN") PostingFilter.PostingStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean includeDuplicates,
            @RequestParam(defaultValue = "SCORE") PostingQueryService.SortBy sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PostingFilter filter = new PostingFilter(minScore, parseSince(since), company, ats, status, q,
                includeDuplicates);
        return queries.search(filter, sort, page, size);
    }

    @GetMapping("/postings/{id}")
    public PostingViews.Detail posting(@PathVariable long id) {
        return queries.detail(id);
    }

    @GetMapping("/companies")
    public List<PostingViews.Board> companies() {
        return queries.boards();
    }

    static Instant parseSince(String since) {
        if (since == null || since.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(since);
        } catch (DateTimeParseException notInstant) {
            try {
                return LocalDate.parse(since).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notDate) {
                throw new IllegalArgumentException("since must be an ISO date or instant, got: " + since);
            }
        }
    }
}
