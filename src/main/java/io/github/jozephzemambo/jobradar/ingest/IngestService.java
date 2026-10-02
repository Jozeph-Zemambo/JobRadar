package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import io.github.jozephzemambo.jobradar.dedup.DedupResult;
import io.github.jozephzemambo.jobradar.dedup.DedupService;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.persistence.IngestRunEntity;
import io.github.jozephzemambo.jobradar.persistence.IngestRunRepository;
import io.github.jozephzemambo.jobradar.persistence.PostingStore;
import io.github.jozephzemambo.jobradar.persistence.SyncCounts;
import io.github.jozephzemambo.jobradar.scoring.Profile;
import io.github.jozephzemambo.jobradar.scoring.Scorer;
import io.github.jozephzemambo.jobradar.source.SourceRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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
    private final PostingStore store;
    private final DedupService dedupService;
    private final Scorer scorer;
    private final Profile profile;
    private final IngestRunRepository runs;
    private final Clock clock;
    /** One ingest at a time: an API call and the scheduled crawl must not sync the same boards concurrently. */
    private final ReentrantLock running = new ReentrantLock();

    public IngestService(SourceRegistry sources, List<FetchStrategy> strategies, PostingStore store,
            DedupService dedupService, Scorer scorer, Profile profile, IngestRunRepository runs,
            JobRadarProperties props, Clock clock) {
        this.sources = sources;
        this.store = store;
        this.dedupService = dedupService;
        this.scorer = scorer;
        this.profile = profile;
        this.runs = runs;
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

    /**
     * Runs one ingest.
     *
     * @throws IngestInProgressException if another ingest is already running
     * @throws IllegalArgumentException  if the request names unknown companies
     */
    public IngestReport ingest(IngestRequest request) {
        if (!running.tryLock()) {
            throw new IngestInProgressException();
        }
        try {
            return runIngest(request);
        } finally {
            running.unlock();
        }
    }

    private IngestReport runIngest(IngestRequest request) {
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        List<Company> companies = resolve(request.companies());
        FetchStrategy strategy = strategies.get(request.mode());
        if (strategy == null) {
            throw new IllegalArgumentException("No fetch strategy for " + request.mode());
        }

        List<FetchOutcome> outcomes = strategy.fetchAll(companies, this::fetchOne);
        Duration fetchTime = Duration.ofNanos(System.nanoTime() - startNanos);

        // Scoring, persistence and dedup run on this one thread after the fan-out: JDBC stays off the virtual threads (some drivers
        // pin them on JDK 21), and each board commits in its own transaction.
        long persistStart = System.nanoTime();
        List<Posting> postings = new ArrayList<>();
        List<IngestReport.CompanyFailure> failures = new ArrayList<>();
        SyncCounts sync = SyncCounts.ZERO;
        for (FetchOutcome outcome : outcomes) {
            switch (outcome) {
                case FetchOutcome.Success success -> {
                    postings.addAll(success.postings());
                    sync = sync.plus(store.syncBoard(success.company(), success.postings(), startedAt,
                            posting -> scorer.score(posting, profile)));
                }
                case FetchOutcome.Failure failure -> failures.add(new IngestReport.CompanyFailure(
                        failure.company().name(), failure.company().boardToken(), failure.company().ats(),
                        failure.errorType(), failure.message()));
            }
        }
        DedupResult dedup = dedupService.refresh();
        IngestReport.DedupCounts dedupCounts = new IngestReport.DedupCounts(dedup.candidates(),
                dedup.count(DedupResult.Reason.EXACT_URL), dedup.count(DedupResult.Reason.FUZZY));
        Duration persistTime = Duration.ofNanos(System.nanoTime() - persistStart);
        Map<Ats, Integer> byAts = postings.stream()
                .collect(Collectors.groupingBy(Posting::ats, () -> new EnumMap<>(Ats.class),
                        Collectors.summingInt(p -> 1)));
        Duration wallTime = Duration.ofNanos(System.nanoTime() - startNanos);

        int succeeded = companies.size() - failures.size();
        IngestRunEntity run = runs.save(new IngestRunEntity(startedAt, request.mode(), companies.size(), succeeded,
                postings.size(), sync, fetchTime.toMillis(), persistTime.toMillis(), wallTime.toMillis()));

        IngestReport report = new IngestReport(run.getId(), startedAt, request.mode(), companies.size(), succeeded,
                List.copyOf(failures), postings.size(), byAts, sync, dedupCounts, fetchTime.toMillis(),
                persistTime.toMillis(), wallTime.toMillis());
        log.info("Ingest {}: {} boards, {} failed, {} postings ({}) in {} ms", request.mode(), companies.size(),
                failures.size(), postings.size(), sync, wallTime.toMillis());
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
