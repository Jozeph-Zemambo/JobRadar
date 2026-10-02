package io.github.jozephzemambo.jobradar.api;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.fixture;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole pipeline against recorded boards: HTTP, parsing, scoring, persistence, dedup, and the read API.
 * Uses its own in-memory database so other Spring tests can't see its rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndToEndIngestTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:e2e-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("jobradar.sources.greenhouse-base-url", wm::baseUrl);
        registry.add("jobradar.sources.lever-base-url", wm::baseUrl);
        registry.add("jobradar.sources.ashby-base-url", wm::baseUrl);
        registry.add("jobradar.http.requests-per-second-per-host", () -> "50");
        registry.add("jobradar.companies[0].name", () -> "Discord");
        registry.add("jobradar.companies[0].ats", () -> "GREENHOUSE");
        registry.add("jobradar.companies[0].board-token", () -> "discord");
        registry.add("jobradar.companies[1].name", () -> "Spotify");
        registry.add("jobradar.companies[1].ats", () -> "LEVER");
        registry.add("jobradar.companies[1].board-token", () -> "spotify");
        registry.add("jobradar.companies[2].name", () -> "Ramp");
        registry.add("jobradar.companies[2].ats", () -> "ASHBY");
        registry.add("jobradar.companies[2].board-token", () -> "ramp");
        registry.add("jobradar.companies[3].name", () -> "Gone");
        registry.add("jobradar.companies[3].ats", () -> "LEVER");
        registry.add("jobradar.companies[3].board-token", () -> "gone");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void ingestThenQuery() throws Exception {
        wm.stubFor(WireMock.get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(okJson(fixture("greenhouse-discord.json"))));
        wm.stubFor(WireMock.get(urlPathEqualTo("/v0/postings/spotify")).willReturn(okJson(fixture("lever-spotify.json"))));
        wm.stubFor(WireMock.get(urlPathEqualTo("/posting-api/job-board/ramp")).willReturn(okJson(fixture("ashby-ramp.json"))));
        // "gone" is not stubbed: WireMock answers 404, like a company that left Lever.

        mvc.perform(post("/api/ingest").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"VIRTUAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companiesRequested").value(4))
                .andExpect(jsonPath("$.companiesSucceeded").value(3))
                .andExpect(jsonPath("$.failures[0].boardToken").value("gone"))
                .andExpect(jsonPath("$.failures[0].errorType").value("BoardNotFoundException"))
                .andExpect(jsonPath("$.postingsFetched").value(9))
                .andExpect(jsonPath("$.sync.created").value(9))
                .andExpect(jsonPath("$.runId").isNumber());

        // Ramp's three engineering roles mention the generalist profile's skills; they should lead the ranking.
        mvc.perform(get("/api/postings").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(9))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].company").value("Ramp"))
                .andExpect(jsonPath("$.items[0].score").isNumber());

        mvc.perform(get("/api/postings").param("ats", "LEVER").param("q", "android"))
                .andExpect(jsonPath("$.items[0].title").value("Android Engineer - Experience"))
                .andExpect(jsonPath("$.items[0].locations[1]").value("Stockholm"));

        mvc.perform(get("/api/companies"))
                .andExpect(jsonPath("$[0].openPostings").value(3))
                .andExpect(jsonPath("$[3].postingsSeen").value(0));

        // A second identical ingest changes nothing but last-seen times.
        mvc.perform(post("/api/ingest"))
                .andExpect(jsonPath("$.sync.created").value(0))
                .andExpect(jsonPath("$.sync.updated").value(9))
                .andExpect(jsonPath("$.sync.closed").value(0));
    }
}
