package io.github.jozephzemambo.jobradar.api;

import io.github.jozephzemambo.jobradar.stats.ExportService;
import io.github.jozephzemambo.jobradar.stats.StatsService;
import io.github.jozephzemambo.jobradar.stats.StatsView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class StatsController {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final StatsService stats;
    private final ExportService export;

    public StatsController(StatsService stats, ExportService export) {
        this.stats = stats;
        this.export = export;
    }

    @GetMapping("/stats")
    public StatsView stats(@RequestParam(defaultValue = "15") int top) {
        return stats.stats(top);
    }

    /** Open, non-duplicate postings as NDJSON, one object per line, streamed in batches. */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) Double minScore,
            @RequestParam(required = false) String since) {
        if (minScore != null && (minScore < 0 || minScore > 1)) {
            throw new IllegalArgumentException("minScore must be between 0 and 1");
        }
        var sinceInstant = PostingController.parseSince(since);
        StreamingResponseBody body = out -> export.export(minScore, sinceInstant, out);
        return ResponseEntity.ok().contentType(NDJSON).body(body);
    }
}
