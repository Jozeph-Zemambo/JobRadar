package io.github.jozephzemambo.jobradar.source.lever;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.fixture;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.mapper;
import static io.github.jozephzemambo.jobradar.source.SourceTestSupport.plainFetcher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.source.BoardNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Replays a trimmed real response from api.lever.co (Spotify, recorded 2026-10-02). */
class LeverSourceContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final Company spotify = new Company("Spotify", Ats.LEVER, "spotify");
    private LeverSource source;

    @BeforeEach
    void setUp() {
        source = new LeverSource(plainFetcher(), mapper(), wm.baseUrl() + "/");
    }

    @Test
    void mapsRecordedBoard() {
        wm.stubFor(get(urlPathEqualTo("/v0/postings/spotify"))
                .withQueryParam("mode", equalTo("json"))
                .willReturn(okJson(fixture("lever-spotify.json"))));

        List<Posting> postings = source.fetch(spotify).postings();

        assertThat(postings).hasSize(3);
        Posting first = postings.getFirst();
        assertThat(first.ats()).isEqualTo(Ats.LEVER);
        assertThat(first.externalId()).isEqualTo("2193db3f-77c5-43b8-b030-8f92c9882bf1");
        assertThat(first.title()).isEqualTo("Android Engineer - Experience");
        assertThat(first.locations()).containsExactly("London", "Stockholm");
        assertThat(first.workplaceType()).isEqualTo(WorkplaceType.HYBRID);
        assertThat(first.department()).isEqualTo("Engineering");
        assertThat(first.url()).isEqualTo("https://jobs.lever.co/spotify/2193db3f-77c5-43b8-b030-8f92c9882bf1");
        assertThat(first.sourcePublishedAt()).isEqualTo(Instant.ofEpochMilli(1782214185805L));
        assertThat(first.compensationSummary()).isNull();
        assertThat(first.descriptionText())
                .startsWith("We design Spotify")
                .contains("What You'll Do")
                .contains("Spotify is an equal opportunity employer")
                .doesNotContain("<li>");

        assertThat(postings.get(2).workplaceType()).isEqualTo(WorkplaceType.ONSITE);
    }

    @Test
    void emptyArrayMeansEmptyBoard() {
        // Seen live for mistral, kraken, whoop: 200 with [] rather than 404.
        wm.stubFor(get(urlPathEqualTo("/v0/postings/spotify")).willReturn(okJson("[]")));
        assertThat(source.fetch(spotify).postings()).isEmpty();
    }

    @Test
    void movedBoardIs404WithLeverErrorBody() {
        // Seen live for plaid and netflix.
        wm.stubFor(get(urlPathEqualTo("/v0/postings/spotify"))
                .willReturn(aResponse().withStatus(404).withBody("{\"ok\":false,\"error\":\"Document not found\"}")));
        assertThatThrownBy(() -> source.fetch(spotify).postings()).isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void fallsBackToSingleLocationAndTeamAndFormatsSalary() {
        wm.stubFor(get(urlPathEqualTo("/v0/postings/spotify")).willReturn(okJson("""
                [{"id":"x1","text":"Backend Engineer","hostedUrl":"https://jobs.lever.co/spotify/x1?lever-source=foo",
                  "categories":{"location":"Toronto","team":"Platform"},
                  "salaryRange":{"currency":"CAD","interval":"per-year-salary","min":120000,"max":160000}}]
                """)));

        Posting posting = source.fetch(spotify).postings().getFirst();

        assertThat(posting.locations()).containsExactly("Toronto");
        assertThat(posting.department()).isEqualTo("Platform");
        assertThat(posting.canonicalUrl()).isEqualTo("https://jobs.lever.co/spotify/x1");
        assertThat(posting.compensationSummary()).isEqualTo("CAD 120,000–160,000 per-year-salary");
        assertThat(posting.workplaceType()).isEqualTo(WorkplaceType.UNKNOWN);
        assertThat(posting.sourcePublishedAt()).isNull();
        assertThat(posting.descriptionText()).isEmpty();
    }

    @Test
    void salaryWithoutBoundsIsIgnored() {
        assertThat(LeverSource.compensation(null)).isNull();
        assertThat(LeverSource.compensation(new LeverDtos.SalaryRange("USD", null, 1.0, null))).isNull();
    }
}
