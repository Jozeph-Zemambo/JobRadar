package io.github.jozephzemambo.jobradar.source.smartrecruiters;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Replays trimmed real responses from api.smartrecruiters.com (Canva, recorded 2026-10-02). */
class SmartRecruitersSourceContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final Company canva = new Company("Canva", Ats.SMARTRECRUITERS, "Canva");

    private SmartRecruitersSource source(int maxDetails) {
        return new SmartRecruitersSource(plainFetcher(), mapper(), wm.baseUrl() + "/", maxDetails);
    }

    @Test
    void mapsRecordedListAndDetails() {
        wm.stubFor(get(urlPathEqualTo("/v1/companies/Canva/postings"))
                .withQueryParam("limit", equalTo("100")).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson(fixture("smartrecruiters-list.json"))));
        String[] ids = {"6000000001455503", "6000000001455481", "6000000001447757"};
        for (int i = 0; i < ids.length; i++) {
            wm.stubFor(get(urlEqualTo("/v1/companies/Canva/postings/" + ids[i]))
                    .willReturn(okJson(fixture("smartrecruiters-detail-" + (i + 1) + ".json"))));
        }

        List<Posting> postings = source(50).fetch(canva);

        assertThat(postings).hasSize(3);
        Posting first = postings.getFirst();
        assertThat(first.ats()).isEqualTo(Ats.SMARTRECRUITERS);
        assertThat(first.externalId()).isEqualTo("6000000001455503");
        assertThat(first.title()).isEqualTo("Engineering Director - Print");
        assertThat(first.locations()).as("empty region dropped").containsExactly("Sydney, Australia");
        assertThat(first.workplaceType()).isEqualTo(WorkplaceType.HYBRID);
        assertThat(first.department()).as("falls back to function").isEqualTo("Information Technology");
        assertThat(first.url()).isEqualTo("https://jobs.smartrecruiters.com/Canva/6000000001455503-engineering-director-print");
        assertThat(first.sourcePublishedAt()).isEqualTo(Instant.parse("2026-10-01T19:29:37.698Z"));
        assertThat(first.descriptionText()).startsWith("About the team").doesNotContain("<p>");
        assertThat(postings.get(1).title()).as("trailing space in live data trimmed").isEqualTo("Marketing Lead, Milan");
    }

    @Test
    void pagesByHundredsAndSkipsDetailsWhenDisabled() {
        wm.stubFor(get(urlPathEqualTo("/v1/companies/Canva/postings")).withQueryParam("offset", equalTo("0"))
                .willReturn(okJson(page(150, 0, 100))));
        wm.stubFor(get(urlPathEqualTo("/v1/companies/Canva/postings")).withQueryParam("offset", equalTo("100"))
                .willReturn(okJson(page(150, 100, 50))));

        List<Posting> postings = source(0).fetch(canva);

        assertThat(postings).hasSize(150);
        Posting remote = postings.getFirst();
        assertThat(remote.workplaceType()).isEqualTo(WorkplaceType.REMOTE);
        assertThat(remote.locations()).containsExactly("Toronto, ON, ca");
        assertThat(remote.url()).isEqualTo("https://jobs.smartrecruiters.com/Canva/id0");
        assertThat(remote.department()).isEqualTo("Engineering");
        assertThat(postings.get(1).workplaceType()).isEqualTo(WorkplaceType.ONSITE);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/v1/companies/Canva/postings")));
        wm.verify(0, getRequestedFor(urlPathMatching("/v1/companies/Canva/postings/.+")));
    }

    @Test
    void unknownCompanyIsAnEmptyBoard() {
        // Live: an unknown identifier returns 200 with totalFound 0, not 404.
        wm.stubFor(get(urlPathEqualTo("/v1/companies/Canva/postings"))
                .willReturn(okJson("{\"offset\":0,\"limit\":100,\"totalFound\":0,\"content\":[]}")));
        assertThat(source(5).fetch(canva)).isEmpty();
    }

    @Test
    void locationAndWorkplaceEdgeCases() {
        assertThat(SmartRecruitersSource.location(null)).isEmpty();
        assertThat(SmartRecruitersSource.location(
                new SmartRecruitersDtos.Location(null, null, null, null, null, " , , "))).isEmpty();
        assertThat(SmartRecruitersSource.workplace(null)).isEqualTo(WorkplaceType.UNKNOWN);
        assertThat(SmartRecruitersSource.workplace(
                new SmartRecruitersDtos.Location("x", null, null, null, null, null))).isEqualTo(WorkplaceType.UNKNOWN);
    }

    private static String page(int total, int start, int count) {
        String items = IntStream.range(start, start + count)
                .mapToObj(i -> "{\"id\":\"id" + i + "\",\"name\":\"Engineer " + i + "\","
                        + "\"location\":{\"city\":\"Toronto\",\"region\":\"ON\",\"country\":\"ca\","
                        + "\"remote\":" + (i == 0) + ",\"hybrid\":false},"
                        + "\"department\":{\"label\":\"Engineering\"}}")
                .collect(Collectors.joining(","));
        return "{\"offset\":" + start + ",\"limit\":100,\"totalFound\":" + total + ",\"content\":[" + items + "]}";
    }
}
