package io.github.jozephzemambo.jobradar.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jozephzemambo.jobradar.ingest.FetchMode;
import io.github.jozephzemambo.jobradar.ingest.IngestInProgressException;
import io.github.jozephzemambo.jobradar.ingest.IngestReport;
import io.github.jozephzemambo.jobradar.ingest.IngestRequest;
import io.github.jozephzemambo.jobradar.ingest.IngestService;
import io.github.jozephzemambo.jobradar.persistence.SyncCounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    IngestService ingestService;

    private final IngestReport report = new IngestReport(1L, Instant.parse("2026-10-02T06:00:00Z"), FetchMode.VIRTUAL,
            3, 3, List.of(), 10, Map.of(), new SyncCounts(10, 0, 0, 0), new IngestReport.DedupCounts(10, 0, 1), 900,
            100, 1000);

    @Test
    void emptyBodyIngestsEverything() throws Exception {
        when(ingestService.ingest(any())).thenReturn(report);

        mvc.perform(post("/api/ingest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallMillis").value(1000))
                .andExpect(jsonPath("$.sync.created").value(10))
                .andExpect(jsonPath("$.dedup.fuzzyDuplicates").value(1));

        verify(ingestService).ingest(IngestRequest.all());
    }

    @Test
    void bodySelectsCompaniesAndMode() throws Exception {
        when(ingestService.ingest(any())).thenReturn(report);

        mvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companies\":[\"ramp\"],\"mode\":\"SEQUENTIAL\"}"))
                .andExpect(status().isOk());

        verify(ingestService).ingest(new IngestRequest(List.of("ramp"), FetchMode.SEQUENTIAL));
    }

    @Test
    void concurrentIngestIs409() throws Exception {
        when(ingestService.ingest(any())).thenThrow(new IngestInProgressException());

        mvc.perform(post("/api/ingest")).andExpect(status().isConflict());
    }

    @Test
    void unknownCompanyIs400() throws Exception {
        when(ingestService.ingest(any())).thenThrow(new IllegalArgumentException("Unknown companies: [nope]"));

        mvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content("{\"companies\":[\"nope\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown companies: [nope]"));
    }
}
