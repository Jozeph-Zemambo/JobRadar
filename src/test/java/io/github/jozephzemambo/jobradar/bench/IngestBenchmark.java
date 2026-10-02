package io.github.jozephzemambo.jobradar.bench;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.http.RateLimiter;
import io.github.jozephzemambo.jobradar.http.TokenBucketRateLimiter;
import io.github.jozephzemambo.jobradar.ingest.FetchMode;
import io.github.jozephzemambo.jobradar.ingest.IngestReport;
import io.github.jozephzemambo.jobradar.ingest.IngestRequest;
import io.github.jozephzemambo.jobradar.ingest.IngestService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ingest wall time: sequential vs. platform thread pool vs. virtual threads, on recorded real board payloads.
 *
 * <p>Run with {@code ./mvnw test -Dtest=IngestBenchmark -Dbench=true} (skipped otherwise). First run records the
 * live Greenhouse/Lever/Ashby payloads of every configured board into {@code bench/recordings/} (git-ignored), one
 * request per second. Each ATS is then served by its own WireMock server (so the per-host limiter sees three hosts,
 * as in production) with a uniform random 150-600 ms delay per response, which matches the live response times
 * measured on 2026-10-02.
 *
 * <p>Matrix: limiter {polite = production default 2 req/s/host burst 2, off} x N {15, 30, 45} x mode, each with one
 * discarded warm-up run and {@link #RUNS} measured runs. The database is emptied before every run so each run does
 * identical persistence work (all inserts). Raw rows go to {@code bench/results/ingest-<date>.csv} and a median
 * summary to {@code bench/results/ingest-summary-<date>.md}.
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
@SpringBootTest
class IngestBenchmark {

    static final int RUNS = 5;
    static final int MIN_DELAY_MS = 150;
    static final int MAX_DELAY_MS = 600;
    static final Path RECORDINGS = Path.of("bench", "recordings");
    static final Path RESULTS = Path.of("bench", "results");
    static final List<Ats> SIMPLE_ATS = List.of(Ats.GREENHOUSE, Ats.LEVER, Ats.ASHBY);

    static final Map<Ats, WireMockServer> SERVERS = new EnumMap<>(Ats.class);

    static {
        for (Ats ats : SIMPLE_ATS) {
            WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort().containerThreads(80)
                    .asynchronousResponseEnabled(true).asynchronousResponseThreads(80));
            server.start();
            SERVERS.put(ats, server);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:bench-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("jobradar.sources.greenhouse-base-url", () -> SERVERS.get(Ats.GREENHOUSE).baseUrl());
        registry.add("jobradar.sources.lever-base-url", () -> SERVERS.get(Ats.LEVER).baseUrl());
        registry.add("jobradar.sources.ashby-base-url", () -> SERVERS.get(Ats.ASHBY).baseUrl());
    }

    /** Lets the benchmark switch between the production limiter and none without restarting Spring. */
    static final class SwitchableLimiter implements RateLimiter {
        volatile RateLimiter delegate = RateLimiter.UNLIMITED;

        @Override
        public void acquire(String key) throws InterruptedException {
            delegate.acquire(key);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SwitchableLimiter switchableLimiter() {
            return new SwitchableLimiter();
        }
    }

    @Autowired
    IngestService ingest;

    @Autowired
    JobRadarProperties props;

    @Autowired
    SwitchableLimiter limiter;

    @Autowired
    JdbcTemplate jdbc;

    @AfterAll
    static void stopServers() {
        SERVERS.values().forEach(WireMockServer::stop);
    }

    record Row(String limiter, int n, FetchMode mode, int run, long wallMillis, long fetchMillis, long persistMillis,
            int postings, int failures) {
    }

    @Test
    void run() throws Exception {
        Map<Ats, List<Company>> byAts = new EnumMap<>(Ats.class);
        for (Ats ats : SIMPLE_ATS) {
            byAts.put(ats, props.companies().stream().filter(c -> c.ats() == ats).toList());
        }
        recordIfMissing(byAts);
        stubRecordings(byAts);

        JobRadarProperties.Http http = props.http();
        Map<String, RateLimiter> limiters = Map.of(
                "polite", new TokenBucketRateLimiter(http.requestsPerSecondPerHost(), http.burstPerHost()),
                "off", RateLimiter.UNLIMITED);

        List<Row> rows = new ArrayList<>();
        for (String limiterName : List.of("off", "polite")) {
            for (int perAts : List.of(5, 10, 15)) {
                List<String> tokens = new ArrayList<>();
                for (Ats ats : SIMPLE_ATS) {
                    byAts.get(ats).stream().limit(perAts).map(Company::boardToken).forEach(tokens::add);
                }
                for (FetchMode mode : FetchMode.values()) {
                    for (int run = 0; run <= RUNS; run++) {
                        // A fresh limiter per run so one run's reservations don't delay the next.
                        limiter.delegate = limiterName.equals("polite")
                                ? new TokenBucketRateLimiter(http.requestsPerSecondPerHost(), http.burstPerHost())
                                : limiters.get("off");
                        jdbc.update("update posting set duplicate_of_id = null");
                        jdbc.update("delete from posting");
                        IngestReport report = ingest.ingest(new IngestRequest(tokens, mode));
                        if (run > 0) { // run 0 is the warm-up
                            rows.add(new Row(limiterName, tokens.size(), mode, run, report.wallMillis(),
                                    report.fetchMillis(), report.persistMillis(), report.postingsFetched(),
                                    report.failures().size()));
                        }
                        System.out.printf("%s n=%d %s run %d: %d ms (%d postings)%n", limiterName, tokens.size(),
                                mode, run, report.wallMillis(), report.postingsFetched());
                    }
                }
            }
        }
        write(rows);
    }

    private void recordIfMissing(Map<Ats, List<Company>> byAts) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (Map.Entry<Ats, List<Company>> entry : byAts.entrySet()) {
            for (Company company : entry.getValue()) {
                Path file = recording(company);
                if (Files.exists(file)) {
                    continue;
                }
                Files.createDirectories(file.getParent());
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(liveUrl(company))
                        .header("User-Agent", props.http().userAgent()).build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Recording " + company.boardToken() + " got " + response.statusCode());
                }
                Files.write(file, response.body());
                System.out.printf("recorded %s (%d bytes)%n", file, response.body().length);
                Thread.sleep(1000); // politeness while recording
            }
        }
    }

    private void stubRecordings(Map<Ats, List<Company>> byAts) {
        for (Map.Entry<Ats, List<Company>> entry : byAts.entrySet()) {
            WireMockServer server = SERVERS.get(entry.getKey());
            for (Company company : entry.getValue()) {
                byte[] body = read(recording(company));
                server.stubFor(get(urlPathEqualTo(path(company))).willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(body)
                        .withUniformRandomDelay(MIN_DELAY_MS, MAX_DELAY_MS)));
            }
        }
    }

    static URI liveUrl(Company c) {
        return switch (c.ats()) {
            case GREENHOUSE -> URI.create("https://boards-api.greenhouse.io" + path(c) + "?content=true");
            case LEVER -> URI.create("https://api.lever.co" + path(c) + "?mode=json");
            case ASHBY -> URI.create("https://api.ashbyhq.com" + path(c) + "?includeCompensation=true");
            default -> throw new IllegalArgumentException("Not benchmarked: " + c.ats());
        };
    }

    static String path(Company c) {
        return switch (c.ats()) {
            case GREENHOUSE -> "/v1/boards/" + c.boardToken() + "/jobs";
            case LEVER -> "/v0/postings/" + c.boardToken();
            case ASHBY -> "/posting-api/job-board/" + c.boardToken();
            default -> throw new IllegalArgumentException("Not benchmarked: " + c.ats());
        };
    }

    static Path recording(Company c) {
        return RECORDINGS.resolve(c.ats().name().toLowerCase(Locale.ROOT)).resolve(c.boardToken() + ".json");
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(List<Row> rows) throws IOException {
        Files.createDirectories(RESULTS);
        String date = LocalDate.now().toString();
        StringBuilder csv = new StringBuilder("limiter,n,mode,run,wall_ms,fetch_ms,persist_ms,postings,failures\n");
        for (Row r : rows) {
            csv.append(String.join(",", r.limiter(), String.valueOf(r.n()), r.mode().name(), String.valueOf(r.run()),
                    String.valueOf(r.wallMillis()), String.valueOf(r.fetchMillis()), String.valueOf(r.persistMillis()),
                    String.valueOf(r.postings()), String.valueOf(r.failures()))).append('\n');
        }
        Files.writeString(RESULTS.resolve("ingest-" + date + ".csv"), csv);

        StringBuilder md = new StringBuilder("""
                | limiter | N boards | mode | median wall ms | min-max wall ms | median fetch ms | median persist ms \
                | postings | speedup vs sequential |
                |---|---|---|---|---|---|---|---|---|
                """);
        Map<String, List<Row>> groups = rows.stream().collect(Collectors.groupingBy(
                r -> r.limiter() + "|" + r.n() + "|" + r.mode(), java.util.LinkedHashMap::new, Collectors.toList()));
        for (List<Row> group : groups.values()) {
            Row first = group.getFirst();
            long median = median(group.stream().map(Row::wallMillis).toList());
            long sequential = median(groups.get(first.limiter() + "|" + first.n() + "|" + FetchMode.SEQUENTIAL)
                    .stream().map(Row::wallMillis).toList());
            md.append(String.format(Locale.ROOT, "| %s | %d | %s | %d | %d-%d | %d | %d | %d | %.1fx |%n",
                    first.limiter(), first.n(), first.mode(), median,
                    group.stream().mapToLong(Row::wallMillis).min().orElseThrow(),
                    group.stream().mapToLong(Row::wallMillis).max().orElseThrow(),
                    median(group.stream().map(Row::fetchMillis).toList()),
                    median(group.stream().map(Row::persistMillis).toList()),
                    first.postings(), sequential / (double) median));
        }
        Files.writeString(RESULTS.resolve("ingest-summary-" + date + ".md"), md);
        System.out.println(md);
    }

    static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
