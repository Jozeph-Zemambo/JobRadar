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
import io.github.jozephzemambo.jobradar.source.BoardSnapshot;
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
import org.springframework.context.ApplicationEventPublisher;
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
    /** Configured boards keyed by "ATS:token": the same token can exist on two ATSes (e.g. mid-migration). */
    private final Map<String, Company> companies;
    private final PostingStore store;
    private final DedupService dedupService;
    private final Scorer scorer;
    private final Profile profile;
    private final IngestRunRepository runs;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    /** One ingest at a time: an API call and the scheduled crawl must not sync the same boards concurrently. */
    private final ReentrantLock running = new ReentrantLock();

    public IngestService(SourceRegistry sources, List<FetchStrategy> strategies, PostingStore store,
            DedupService dedupService, Scorer scorer, Profile profile, IngestRunRepository runs,
            JobRadarProperties props, Clock clock, ApplicationEventPublisher events) {
        this.sources = sources;
        this.events = events;
        this.store = store;
        this.dedupService = dedupService;
        this.scorer = scorer;
        this.profile = profile;
        this.runs = runs;
        this.strategies = new EnumMap<>(FetchMode.class);
        for (FetchStrategy strategy : strategies) {
            this.strategies.put(strategy.mode(), strategy);
        }
        this.companies = new LinkedHashMap<>();
        for (Company company : props.companies()) {
            this.companies.putIfAbsent(key(company.ats(), company.boardToken()), company);
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

        // Scoring, persistence and dedup run on this one thread after the fan-out: JDBC stays off the virtual
        // threads (some drivers pin them on JDK 21), and each board commits in its own transaction.
        long persistStart = System.nanoTime();
        List<Posting> postings = new ArrayList<>();
        List<IngestReport.CompanyFailure> failures = new ArrayList<>();
        List<IngestReport.IncompleteBoard> incomplete = new ArrayList<>();
        SyncCounts sync = SyncCounts.ZERO;
        boolean wroteAny = false;
        Long runId = null;
        try {
            for (FetchOutcome outcome : outcomes) {
                switch (outcome) {
                    case FetchOutcome.Success success -> {
                        Company company = success.company();
                        BoardSnapshot snapshot = success.snapshot();
                        postings.addAll(snapshot.postings());
                        try {
                            sync = sync.plus(store.syncBoard(company, snapshot, startedAt,
                                    posting -> scorer.score(posting, profile)));
                            wroteAny = true;
                            if (!snapshot.complete()) {
                                incomplete.add(new IngestReport.IncompleteBoard(company.boardToken(), company.ats(),
                                        snapshot.incompleteReason()));
                            }
                        } catch (RuntimeException e) {
                            // A database error on one board (its transaction rolled back) must not stop the rest.
                            log.error("Storing {} ({}) failed", company.boardToken(), company.ats(), e);
                            failures.add(new IngestReport.CompanyFailure(company.name(), company.boardToken(),
                                    company.ats(), "PersistenceFailure", e.getMessage()));
                        }
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
            IngestRunEntity run = runs.save(new IngestRunEntity(startedAt, request.mode(), companies.size(),
                    succeeded, postings.size(), sync, fetchTime.toMillis(), persistTime.toMillis(),
                    wallTime.toMillis()));
            runId = run.getId();

            IngestReport report = new IngestReport(runId, startedAt, request.mode(), companies.size(), succeeded,
                    List.copyOf(failures), List.copyOf(incomplete), postings.size(), byAts, sync, dedupCounts,
                    fetchTime.toMillis(), persistTime.toMillis(), wallTime.toMillis());
            log.info("Ingest {}: {} boards, {} failed, {} incomplete, {} postings ({}) in {} ms", request.mode(),
                    companies.size(), failures.size(), incomplete.size(), postings.size(), sync, wallTime.toMillis());
            return report;
        } finally {
            // Even if a later step throws, boards already committed changed the data: listeners (the stats cache)
            // must hear about it.
            if (wroteAny) {
                events.publishEvent(new IngestCompletedEvent(runId));
            }
        }
    }

    /** Fetches one board and never throws: this is the per-board fault boundary. */
    FetchOutcome fetchOne(Company company) {
        long start = System.nanoTime();
        try {
            BoardSnapshot snapshot = sources.forAts(company.ats()).fetch(company);
            return new FetchOutcome.Success(company, snapshot, Duration.ofNanos(System.nanoTime() - start));
        } catch (RuntimeException e) {
            log.warn("Fetching {} ({}) failed: {}", company.boardToken(), company.ats(), e.getMessage());
            return new FetchOutcome.Failure(company, e.getClass().getSimpleName(), e.getMessage(),
                    Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * Resolves requested boards. Each entry is a board token ("stripe"), which selects that token on every ATS
     * that has it, or "ATS:token" ("LEVER:acme") to pick one.
     */
    private List<Company> resolve(List<String> requested) {
        if (requested.isEmpty()) {
            return List.copyOf(companies.values());
        }
        Map<String, Company> selected = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String entry : requested) {
            List<Company> matches = companies.values().stream()
                    .filter(c -> entry.equals(c.boardToken()) || entry.equalsIgnoreCase(key(c.ats(), c.boardToken())))
                    .toList();
            if (matches.isEmpty()) {
                unknown.add(entry);
            }
            matches.forEach(c -> selected.putIfAbsent(key(c.ats(), c.boardToken()), c));
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown companies: " + unknown);
        }
        return List.copyOf(selected.values());
    }

    private static String key(Ats ats, String boardToken) {
        return ats + ":" + boardToken;
    }

    /** Every configured board, in configuration order. */
    public List<Company> configuredCompanies() {
        return List.copyOf(companies.values());
    }
}
