package io.github.jozephzemambo.jobradar.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.query.PostingFilter;
import io.github.jozephzemambo.jobradar.query.PostingQueryService;
import io.github.jozephzemambo.jobradar.query.PostingViews;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PostingController.class)
class PostingControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PostingQueryService queries;

    private final PostingViews.Summary summary = new PostingViews.Summary(7, Ats.ASHBY, "Ramp",
            "Software Engineer, Frontend", List.of("New York, NY (HQ)"), WorkplaceType.HYBRID, "Engineering",
            "https://jobs.ashbyhq.com/ramp/x", 0.81, List.of("React", "TypeScript"),
            Instant.parse("2026-10-02T06:00:00Z"), null, null);

    @Test
    void passesFiltersThroughAndReturnsAPage() throws Exception {
        when(queries.search(any(), any(), anyInt(), anyInt()))
                .thenReturn(new PostingViews.PageOf<>(List.of(summary), 0, 20, 1, 1));

        mvc.perform(get("/api/postings").param("minScore", "0.5").param("since", "2026-10-01")
                        .param("ats", "ASHBY").param("company", "Ramp").param("q", "frontend").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(7))
                .andExpect(jsonPath("$.items[0].score").value(0.81))
                .andExpect(jsonPath("$.items[0].matchedSkills[0]").value("React"))
                .andExpect(jsonPath("$.totalItems").value(1));

        verify(queries).search(eq(new PostingFilter(0.5, Instant.parse("2026-10-01T00:00:00Z"), "Ramp", Ats.ASHBY,
                PostingFilter.PostingStatus.OPEN, "frontend", false)), eq(PostingQueryService.SortBy.SCORE), eq(0),
                eq(20));
    }

    @Test
    void sinceAcceptsAnInstant() throws Exception {
        when(queries.search(any(), any(), anyInt(), anyInt())).thenReturn(new PostingViews.PageOf<>(List.of(), 0, 50, 0, 0));

        mvc.perform(get("/api/postings").param("since", "2026-10-01T12:30:00Z").param("sort", "NEWEST"))
                .andExpect(status().isOk());

        verify(queries).search(eq(new PostingFilter(null, Instant.parse("2026-10-01T12:30:00Z"), null, null, null,
                null, false)), eq(PostingQueryService.SortBy.NEWEST), eq(0), eq(50));
    }

    @Test
    void badSinceIsAProblem400() throws Exception {
        mvc.perform(get("/api/postings").param("since", "last tuesday"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("since must be an ISO date or instant, got: last tuesday"));
    }

    @Test
    void outOfRangeScoreAndUnknownEnumAre400() throws Exception {
        mvc.perform(get("/api/postings").param("minScore", "3")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/postings").param("ats", "LINKEDIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid value 'LINKEDIN' for parameter 'ats'"));
    }

    @Test
    void missingPostingIs404() throws Exception {
        when(queries.detail(99)).thenThrow(new NoSuchElementException("No posting 99"));

        mvc.perform(get("/api/postings/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void listsCompanies() throws Exception {
        when(queries.boards()).thenReturn(List.of(new PostingViews.Board("Ramp", Ats.ASHBY, "ramp", 158, 150, null)));

        mvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].openPostings").value(150));
    }
}
