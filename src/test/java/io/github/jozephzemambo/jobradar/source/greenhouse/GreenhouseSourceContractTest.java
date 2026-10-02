package io.github.jozephzemambo.jobradar.source.greenhouse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import io.github.jozephzemambo.jobradar.source.MalformedResponseException;
import io.github.jozephzemambo.jobradar.source.UpstreamException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Replays a trimmed real response from boards-api.greenhouse.io (Discord, recorded 2026-10-02). */
class GreenhouseSourceContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final Company discord = new Company("Discord", Ats.GREENHOUSE, "discord");
    private GreenhouseSource source;

    @BeforeEach
    void setUp() {
        source = new GreenhouseSource(plainFetcher(), mapper(), wm.baseUrl());
    }

    @Test
    void mapsRecordedBoard() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs"))
                .withQueryParam("content", equalTo("true"))
                .willReturn(okJson(fixture("greenhouse-discord.json"))));

        List<Posting> postings = source.fetch(discord).postings();

        assertThat(postings).hasSize(3);
        Posting first = postings.getFirst();
        assertThat(first.ats()).isEqualTo(Ats.GREENHOUSE);
        assertThat(first.externalId()).isEqualTo("8806482002");
        assertThat(first.company()).isEqualTo("Discord");
        assertThat(first.title()).isEqualTo("Commercial Policy Lead");
        assertThat(first.locations()).containsExactly("San Francisco Bay Area");
        assertThat(first.department()).isEqualTo("Policy");
        assertThat(first.url()).isEqualTo("https://job-boards.greenhouse.io/discord/jobs/8806482002");
        assertThat(first.canonicalUrl()).isEqualTo(first.url());
        assertThat(first.sourcePublishedAt()).isEqualTo(Instant.parse("2026-09-15T16:52:11Z"));
        assertThat(first.workplaceType()).isEqualTo(WorkplaceType.UNKNOWN);
        assertThat(first.descriptionText())
                .startsWith("Discord has a highly engaged")
                .doesNotContain("&lt;", "<div", "<p>");

        assertThat(postings.get(2).workplaceType()).as("'(or Remote U.S.)' in location").isEqualTo(WorkplaceType.REMOTE);
        wm.verify(getRequestedFor(urlPathEqualTo("/v1/boards/discord/jobs"))
                .withHeader("User-Agent", equalTo("JobRadar-test")));
    }

    @Test
    void bodyWithoutJobsOrNullBodyIsMalformedNotAnEmptyBoard() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(okJson("{}")));
        assertThatThrownBy(() -> source.fetch(discord)).isInstanceOf(MalformedResponseException.class);

        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(okJson("null")));
        assertThatThrownBy(() -> source.fetch(discord)).isInstanceOf(MalformedResponseException.class);
    }

    @Test
    void skippingAMalformedPostingMarksTheSnapshotIncomplete() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(okJson("""
                {"jobs":[{"id":1,"title":"Engineer","absolute_url":"https://x.io/1"},{"id":456,"title":null}]}
                """)));

        var snapshot = source.fetch(discord);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.incompleteReason()).contains("1 postings could not be parsed");
        assertThat(snapshot.postings()).extracting(Posting::externalId).containsExactly("1");
    }

    @Test
    void ignoresUnknownFieldsAndSkipsPostingsMissingRequiredData() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(okJson("""
                {"jobs":[
                  {"id":1,"title":"Engineer","absolute_url":"https://x.io/1","brand_new_field":{"a":1}},
                  {"id":2,"title":"   ","absolute_url":"https://x.io/2"},
                  {"id":3,"title":"No URL"}
                ],"meta":{"total":3}}
                """)));

        List<Posting> postings = source.fetch(discord).postings();

        assertThat(postings).extracting(Posting::externalId).containsExactly("1");
        assertThat(postings.getFirst().sourcePublishedAt()).isNull();
    }

    @Test
    void emptyBoardYieldsNoPostings() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(okJson("{\"jobs\":[],\"meta\":{\"total\":0}}")));
        assertThat(source.fetch(discord).postings()).isEmpty();
    }

    @Test
    void notFoundBecomesBoardNotFound() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs")).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> source.fetch(discord).postings()).isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void htmlInsteadOfJsonIsMalformed() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs"))
                .willReturn(aResponse().withStatus(200).withBody("<html>maintenance</html>")));
        assertThatThrownBy(() -> source.fetch(discord).postings()).isInstanceOf(MalformedResponseException.class);
    }

    @Test
    void serverErrorCarriesStatusAndRetryAfter() {
        wm.stubFor(get(urlPathEqualTo("/v1/boards/discord/jobs"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));

        assertThatThrownBy(() -> source.fetch(discord).postings())
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(429);
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.retryAfter()).hasValueSatisfying(d -> assertThat(d.toSeconds()).isEqualTo(7));
                });
    }
}
