package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.source.SourceRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs an ingest: picks the boards, fans out with the requested {@link FetchStrategy}, and summarizes.
 * Each board's fetch is isolated: whatever one board throws becomes a {@link FetchOutcome.Failure}.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final SourceRegistry sources;
    private final Map<FetchMode, FetchStrategy> strategies;
    private final Map<String, Company> companiesByToken;
    private final Clock clock;

    public IngestService(SourceRegistry sources, List<FetchStrategy> strategies, JobRadarProperties props,
            Clock clock) {
        this.sources = sources;
        this.strategies = new EnumMap<>(FetchMode.class);
        for (FetchStrategy strategy : strategies) {
            this.strategies.put(strategy.mode(), strategy);
        }
        this.companiesByToken = new LinkedHashMap<>();
        for (Company company : props.companies()) {
            this.companiesByToken.put(company.boardToken(), company);
        }
        this.clock = clock;
    }

    public IngestReport ingest(IngestRequest request) {
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        List<Company> companies = resolve(request.companies());
        FetchStrategy strategy = strategies.get(request.mode());
        if (strategy == null) {
            throw new IllegalArgumentException("No fetch strategy for " + request.mode());
        }

        List<FetchOutcome> outcomes = strategy.fetchAll(companies, this::fetchOne);
        Duration fetchTime = Duration.ofNanos(System.nanoTime() - startNanos);

        List<Posting> postings = new ArrayList<>();
        List<IngestReport.CompanyFailure> failures = new ArrayList<>();
        for (FetchOutcome outcome : outcomes) {
            switch (outcome) {
                case FetchOutcome.Success success -> postings.addAll(success.postings());
                case FetchOutcome.Failure failure -> failures.add(new IngestReport.CompanyFailure(
                        failure.company().name(), failure.company().boardToken(), failure.company().ats(),
                        failure.errorType(), failure.message()));
            }
        }
        Map<Ats, Integer> byAts = postings.stream()
                .collect(Collectors.groupingBy(Posting::ats, () -> new EnumMap<>(Ats.class),
                        Collectors.summingInt(p -> 1)));

        IngestReport report = new IngestReport(startedAt, request.mode(), companies.size(),
                companies.size() - failures.size(), List.copyOf(failures), postings.size(), byAts, fetchTime,
                Duration.ofNanos(System.nanoTime() - startNanos));
        log.info("Ingest {}: {} boards, {} failed, {} postings in {} ms", request.mode(), companies.size(),
                failures.size(), postings.size(), report.wallTime().toMillis());
        return report;
    }

    /** Fetches one board and never throws: this is the per-board fault boundary. */
    FetchOutcome fetchOne(Company company) {
        long start = System.nanoTime();
        try {
            List<Posting> postings = sources.forAts(company.ats()).fetch(company);
            return new FetchOutcome.Success(company, postings, Duration.ofNanos(System.nanoTime() - start));
        } catch (RuntimeException e) {
            log.warn("Fetching {} ({}) failed: {}", company.boardToken(), company.ats(), e.getMessage());
            return new FetchOutcome.Failure(company, e.getClass().getSimpleName(), e.getMessage(),
                    Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private List<Company> resolve(List<String> tokens) {
        if (tokens.isEmpty()) {
            return List.copyOf(companiesByToken.values());
        }
        List<String> unknown = tokens.stream().filter(t -> !companiesByToken.containsKey(t)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown companies: " + unknown);
        }
        return tokens.stream().distinct().map(companiesByToken::get).toList();
    }

    /** Visible for the benchmark harness, which fetches subsets of the configured list. */
    public List<Company> configuredCompanies() {
        return List.copyOf(companiesByToken.values());
    }
}
