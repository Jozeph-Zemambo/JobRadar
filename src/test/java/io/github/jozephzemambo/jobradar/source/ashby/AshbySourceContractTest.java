package io.github.jozephzemambo.jobradar.source.ashby;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.fixture;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.mapper;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.plainFetcher;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Replays a trimmed real response from api.ashbyhq.com (Ramp, recorded 2026-10-02). */
class AshbySourceContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final Company ramp = new Company("Ramp", Ats.ASHBY, "ramp");
    private AshbySource source;

    @BeforeEach
    void setUp() {
        source = new AshbySource(plainFetcher(), mapper(), wm.baseUrl());
    }

    @Test
    void mapsRecordedBoard() {
        wm.stubFor(get(urlPathEqualTo("/posting-api/job-board/ramp"))
                .withQueryParam("includeCompensation", equalTo("true"))
                .willReturn(okJson(fixture("ashby-ramp.json"))));

        List<Posting> postings = source.fetch(ramp);

        assertThat(postings).hasSize(3);
        Posting first = postings.getFirst();
        assertThat(first.ats()).isEqualTo(Ats.ASHBY);
        assertThat(first.externalId()).isEqualTo("34413f8d-26bf-4bbc-8ade-eb309a0e2245");
        assertThat(first.title()).as("leading space in the live data is trimmed").isEqualTo("Security Engineer, Cloud");
        assertThat(first.locations())
                .containsExactly("New York, NY (HQ)", "Remote (Canada)", "Remote (US)", "Miami, FL");
        assertThat(first.workplaceType()).isEqualTo(WorkplaceType.HYBRID);
        assertThat(first.department()).isEqualTo("Engineering");
        assertThat(first.url()).isEqualTo("https://jobs.ashbyhq.com/ramp/34413f8d-26bf-4bbc-8ade-eb309a0e2245");
        assertThat(first.sourcePublishedAt()).isEqualTo(Instant.parse("2026-04-07T17:12:35.753Z"));
        assertThat(first.compensationSummary()).isEqualTo("$211.4K – $290.6K • Offers Equity");
        assertThat(first.descriptionText()).startsWith("ABOUT RAMP");
    }

    @Test
    void dropsUnlistedAndFallsBackForRemoteAndHtmlDescription() {
        wm.stubFor(get(urlPathEqualTo("/posting-api/job-board/ramp")).willReturn(okJson("""
                {"apiVersion":"1","jobs":[
                  {"id":"a","title":"Hidden","isListed":false,"jobUrl":"https://jobs.ashbyhq.com/ramp/a"},
                  {"id":"b","title":"Remote Engineer","isListed":true,"isRemote":true,"team":"Infra",
                   "jobUrl":"https://jobs.ashbyhq.com/ramp/b","descriptionHtml":"<p>Use <b>Go</b></p>",
                   "secondaryLocations":[{"location":" "}]}
                ]}
                """)));

        List<Posting> postings = source.fetch(ramp);

        assertThat(postings).extracting(Posting::externalId).containsExactly("b");
        Posting posting = postings.getFirst();
        assertThat(posting.workplaceType()).isEqualTo(WorkplaceType.REMOTE);
        assertThat(posting.department()).isEqualTo("Infra");
        assertThat(posting.locations()).isEmpty();
        assertThat(posting.descriptionText()).isEqualTo("Use Go");
    }

    @Test
    void missingJobsArrayIsEmptyBoard() {
        wm.stubFor(get(urlPathEqualTo("/posting-api/job-board/ramp")).willReturn(okJson("{\"apiVersion\":\"1\"}")));
        assertThat(source.fetch(ramp)).isEmpty();
    }
}
