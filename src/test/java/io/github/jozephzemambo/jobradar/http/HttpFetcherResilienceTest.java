package io.github.jozephzemambo.jobradar.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.jozephzemambo.jobradar.source.BoardNotFoundException;
import io.github.jozephzemambo.jobradar.source.UpstreamException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** End-to-end over real HTTP: rate limiter and retry policy wired into the fetcher as in production. */
class HttpFetcherResilienceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> limiterKeys = new ArrayList<>();
    private final HttpFetcher fetcher = new HttpFetcher(HttpClient.newHttpClient(), "JobRadar-test",
            Duration.ofSeconds(5), limiterKeys::add,
            new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1), sleeps::add, new SplittableRandom(7)));

    @Test
    void recoversFromTwo503s() {
        wm.stubFor(get(urlEqualTo("/board")).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        wm.stubFor(get(urlEqualTo("/board")).inScenario("flaky").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("healthy"));
        wm.stubFor(get(urlEqualTo("/board")).inScenario("flaky").whenScenarioStateIs("healthy")
                .willReturn(ok("[]")));

        byte[] body = fetcher.get(URI.create(wm.baseUrl() + "/board"));

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("[]");
        wm.verify(3, getRequestedFor(urlEqualTo("/board")));
        assertThat(sleeps).hasSize(2);
        assertThat(limiterKeys).as("every attempt is rate limited, retries included")
                .hasSize(3).containsOnly("localhost:" + wm.getPort());
    }

    @Test
    void notFoundIsNeverRetried() {
        wm.stubFor(get(urlEqualTo("/gone")).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> fetcher.get(URI.create(wm.baseUrl() + "/gone")))
                .isInstanceOf(BoardNotFoundException.class);
        wm.verify(1, getRequestedFor(urlEqualTo("/gone")));
    }

    @Test
    void connectionResetIsRetriedThenSurfaced() {
        wm.stubFor(get(urlEqualTo("/reset")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> fetcher.get(URI.create(wm.baseUrl() + "/reset")))
                .isInstanceOfSatisfying(UpstreamException.class,
                        e -> assertThat(e.statusCode()).isEqualTo(UpstreamException.NO_RESPONSE));
        wm.verify(3, getRequestedFor(urlEqualTo("/reset")));
    }

    @Test
    void parsesRetryAfterSeconds() {
        assertThat(HttpFetcher.parseRetryAfter(" 12 ")).isEqualTo(Duration.ofSeconds(12));
        assertThat(HttpFetcher.parseRetryAfter("-1")).isNull();
        assertThat(HttpFetcher.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isNull();
    }
}
