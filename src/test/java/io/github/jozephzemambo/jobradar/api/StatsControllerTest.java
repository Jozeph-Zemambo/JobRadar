package io.github.jozephzemambo.jobradar.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jozephzemambo.jobradar.stats.ExportService;
import io.github.jozephzemambo.jobradar.stats.StatsService;
import io.github.jozephzemambo.jobradar.stats.StatsView;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(StatsController.class)
class StatsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    StatsService stats;

    @MockitoBean
    ExportService export;

    @Test
    void statsUsesTopParameter() throws Exception {
        when(stats.stats(5)).thenReturn(new StatsView(10, 2, 1, Map.of("LEVER", 10L), Map.of(), List.of(),
                List.of(new StatsView.SkillDemand("Java", "language", 4, 0.4)),
                new StatsView.TimeToClose(0, null, null), 3, new StatsView.Crawl(1, null, null, 51)));

        mvc.perform(get("/api/stats").param("top", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openPostings").value(10))
                .andExpect(jsonPath("$.topSkills[0].share").value(0.4))
                .andExpect(jsonPath("$.timeToClose.medianDays").isEmpty());
    }

    @Test
    void exportStreamsNdjson() throws Exception {
        when(export.export(eq(0.5), isNull(), any())).thenAnswer(inv -> {
            OutputStream out = inv.getArgument(2);
            out.write("{\"a\":1}\n{\"a\":2}\n".getBytes(StandardCharsets.UTF_8));
            return 2L;
        });

        MvcResult started = mvc.perform(get("/api/export").param("minScore", "0.5"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/x-ndjson"))
                .andExpect(content().string("{\"a\":1}\n{\"a\":2}\n"));
    }

    @Test
    void exportRejectsBadScore() throws Exception {
        mvc.perform(get("/api/export").param("minScore", "-1")).andExpect(status().isBadRequest());
    }
}
