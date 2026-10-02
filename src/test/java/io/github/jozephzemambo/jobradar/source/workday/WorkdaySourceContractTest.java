package io.github.jozephzemambo.jobradar.source.workday;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import io.github.jozephzemambo.jobradar.source.UpstreamException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Replays trimmed real responses from workday.wd5.myworkdayjobs.com (recorded 2026-10-02). */
class WorkdaySourceContractTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String CXS = "/wday/cxs/workday/Workday";
    private final Company workday = new Company("Workday", Ats.WORKDAY, "workday/wd5/Workday");

    private WorkdaySource source(int maxDetails) {
        return new WorkdaySource(plainFetcher(), mapper(), wm.baseUrl(), maxDetails);
    }

    @Test
    void mapsRecordedListAndDetails() {
        wm.stubFor(post(urlEqualTo(CXS + "/jobs"))
                .withRequestBody(equalToJson("{\"appliedFacets\":{},\"limit\":20,\"offset\":0,\"searchText\":\"\"}"))
                .willReturn(okJson(fixture("workday-list.json"))));
        String[] paths = {
            "/job/USA-CA-Pleasanton/Director--Software-Development-Engineering---PE-Platforms_JR-0109619",
            "/job/USAVAReston/Software-Engineer---DevOps--US-Federal-_JR-0110541-1",
            "/job/USA-CA-Pleasanton/Principal--Product-Manager_JR-0110096"};
        for (int i = 0; i < paths.length; i++) {
            wm.stubFor(get(urlEqualTo(CXS + paths[i])).willReturn(okJson(fixture("workday-detail-" + (i + 1) + ".json"))));
        }

        List<Posting> postings = source(50).fetch(workday);

        assertThat(postings).hasSize(3);
        Posting devops = postings.get(1);
        assertThat(devops.ats()).isEqualTo(Ats.WORKDAY);
        assertThat(devops.externalId()).isEqualTo(paths[1]);
        assertThat(devops.title()).isEqualTo("Software Engineer - DevOps (US Federal)");
        assertThat(devops.locations()).containsExactly("USA.VA.Reston");
        assertThat(devops.workplaceType()).isEqualTo(WorkplaceType.HYBRID);
        assertThat(devops.url()).isEqualTo("https://workday.wd5.myworkdayjobs.com/Workday" + paths[1]);
        assertThat(devops.sourcePublishedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(devops.descriptionText()).isNotBlank().doesNotContain("<p>");
        wm.verify(postRequestedFor(urlEqualTo(CXS + "/jobs")).withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    void pagesUntilTheFirstPageTotalUsingTwentyPerPage() {
        // Live quirk: "total" is only set on the first page; later pages report 0.
        wm.stubFor(post(urlEqualTo(CXS + "/jobs")).withRequestBody(equalToJson("{\"offset\":0}", true, true))
                .willReturn(okJson(page(45, 0, 20))));
        wm.stubFor(post(urlEqualTo(CXS + "/jobs")).withRequestBody(equalToJson("{\"offset\":20}", true, true))
                .willReturn(okJson(page(0, 20, 20))));
        wm.stubFor(post(urlEqualTo(CXS + "/jobs")).withRequestBody(equalToJson("{\"offset\":40}", true, true))
                .willReturn(okJson(page(0, 40, 5))));

        List<Posting> postings = source(0).fetch(workday);

        assertThat(postings).hasSize(45);
        assertThat(postings.getFirst().locations()).containsExactly("Remote, US");
        assertThat(postings.get(1).locations()).as("'2 Locations' carries no information").isEmpty();
        assertThat(postings.getFirst().descriptionText()).isEmpty();
        assertThat(postings.getFirst().url()).isEqualTo(wm.baseUrl() + "/Workday/job/p0");
        wm.verify(3, postRequestedFor(urlEqualTo(CXS + "/jobs")));
        wm.verify(0, getRequestedFor(urlPathMatching(".*/job/.*")));
    }

    @Test
    void detailsOnlyForTheFirstNAndA404DetailFallsBackToListFields() {
        wm.stubFor(post(urlEqualTo(CXS + "/jobs")).willReturn(okJson(page(5, 0, 5))));
        wm.stubFor(get(urlEqualTo(CXS + "/job/p0")).willReturn(okJson("""
                {"jobPostingInfo":{"title":"Engineer 0","jobDescription":"<p>Java</p>","location":"Berlin",
                 "additionalLocations":["Munich"],"remoteType":"Fully Remote",
                 "externalUrl":"https://workday.wd5.myworkdayjobs.com/Workday/job/p0"}}""")));
        wm.stubFor(get(urlEqualTo(CXS + "/job/p1")).willReturn(aResponse().withStatus(404)));

        List<Posting> postings = source(2).fetch(workday);

        assertThat(postings).hasSize(5);
        assertThat(postings.get(0).descriptionText()).isEqualTo("Java");
        assertThat(postings.get(0).locations()).containsExactly("Berlin", "Munich");
        assertThat(postings.get(0).workplaceType()).isEqualTo(WorkplaceType.REMOTE);
        assertThat(postings.get(1).title()).isEqualTo("Engineer 1");
        assertThat(postings.get(1).descriptionText()).isEmpty();
        wm.verify(2, getRequestedFor(urlPathMatching(".*/job/.*")));
    }

    @Test
    void failingDetailFailsTheBoard() {
        wm.stubFor(post(urlEqualTo(CXS + "/jobs")).willReturn(okJson(page(1, 0, 1))));
        wm.stubFor(get(urlEqualTo(CXS + "/job/p0")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> source(5).fetch(workday)).isInstanceOf(UpstreamException.class);
    }

    @Test
    void boardTokenMustBeTenantWdSite() {
        Company bad = new Company("X", Ats.WORKDAY, "workday");
        assertThatThrownBy(() -> source(0).fetch(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThat(WorkdaySource.Board.parse("intel/wd1/External"))
                .isEqualTo(new WorkdaySource.Board("intel", "wd1", "External"));
    }

    @Test
    void productionTemplateBuildsTenantHost() {
        WorkdaySource live = new WorkdaySource(plainFetcher(), mapper(), "https://{tenant}.{wd}.myworkdayjobs.com", 0);
        assertThat(live.detailUrl(workday, new WorkdayDtos.ListItem("t", "/job/x", null, null, null)).toString())
                .isEqualTo("https://workday.wd5.myworkdayjobs.com/wday/cxs/workday/Workday/job/x");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", textBlock = """
            Flex, HYBRID
            Hybrid, HYBRID
            Fully Remote, REMOTE
            On-Site, ONSITE
            Office Based, ONSITE
            Something, UNKNOWN
            null, UNKNOWN
            """)
    void remoteTypeVocabulary(String raw, WorkplaceType expected) {
        assertThat(WorkdaySource.workplace(raw)).isEqualTo(expected);
    }

    private static String page(int total, int start, int count) {
        String items = IntStream.range(start, start + count)
                .mapToObj(i -> "{\"title\":\"Engineer " + i + "\",\"externalPath\":\"/job/p" + i + "\","
                        + "\"locationsText\":\"" + (i % 2 == 0 ? "Remote, US" : "2 Locations") + "\"}")
                .collect(Collectors.joining(","));
        return "{\"total\":" + total + ",\"jobPostings\":[" + items + "]}";
    }
}
