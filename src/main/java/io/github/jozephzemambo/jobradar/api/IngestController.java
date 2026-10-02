package io.github.jozephzemambo.jobradar.api;

import io.github.jozephzemambo.jobradar.ingest.IngestReport;
import io.github.jozephzemambo.jobradar.ingest.IngestRequest;
import io.github.jozephzemambo.jobradar.ingest.IngestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Runs an ingest synchronously and returns its report. An empty body ingests every configured board on
     * virtual threads. Returns 409 if an ingest is already running.
     */
    @PostMapping("/ingest")
    public IngestReport ingest(@RequestBody(required = false) IngestRequest request) {
        return ingestService.ingest(request == null ? IngestRequest.all() : request);
    }
}
