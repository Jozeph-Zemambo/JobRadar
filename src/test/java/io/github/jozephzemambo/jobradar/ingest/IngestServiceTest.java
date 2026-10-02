package io.github.jozephzemambo.jobradar.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import io.github.jozephzemambo.jobradar.scoring.ScoreBreakdown;
import io.github.jozephzemambo.jobradar.scoring.ScoredPosting;
import io.github.jozephzemambo.jobradar.scoring.Scorer;
import io.github.jozephzemambo.jobradar.source.BoardNotFoundException;
import io.github.jozephzemambo.jobradar.source.JobSource;
import io.github.jozephzemambo.jobradar.source.SourceRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class IngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private final Company stripe = new Company("Stripe", Ats.GREENHOUSE, "stripe");
    private final Company plaid = new Company("Plaid", Ats.LEVER, "plaid");
    private final Company ramp = new Company("Ramp", Ats.ASHBY, "ramp");

    private final JobSource greenhouse = mock(JobSource.class);
    private final JobSource lever = mock(JobSource.class);
    private final JobSource ashby = mock(JobSource.class);
    private final PostingStore store = mock(PostingStore.class);
    private final IngestRunRepository runs = mock(IngestRunRepository.class);
    private final DedupService dedupService = mock(DedupService.class);
    private final Profile profile = new Profile("p", List.of("Java"), List.of("engineer"), List.of());
    private final Scorer scorer = (posting, prof) -> new ScoreBreakdown(0.5, 0.5, true, false, List.of(),
            List.of(), List.of());
    private IngestService service;

    @BeforeEach
    void setUp() {
        when(greenhouse.ats()).thenReturn(Ats.GREENHOUSE);
        when(lever.ats()).thenReturn(Ats.LEVER);
        when(ashby.ats()).thenReturn(Ats.ASHBY);
        when(store.syncBoard(any(), any(), any()))
                .thenAnswer(inv -> new SyncCounts(inv.<List<?>>getArgument(1).size(), 0, 0, 0));
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dedupService.refresh()).thenReturn(new DedupResult(3, List.of(), 0));
        JobRadarProperties props = new JobRadarProperties(null, null, null, List.of(stripe, plaid, ramp), null, null);
        service = new IngestService(new SourceRegistry(List.of(greenhouse, lever, ashby)),
                List.of(new SequentialFetchStrategy(), new PlatformPoolFetchStrategy(2),
                        new VirtualThreadFetchStrategy()),
                store, dedupService, scorer, profile, runs, props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @ParameterizedTest
    @EnumSource(FetchMode.class)
    void oneFailingBoardDoesNotAffectTheOthers(FetchMode mode) {
        when(greenhouse.fetch(stripe)).thenReturn(List.of(posting(Ats.GREENHOUSE, "1"), posting(Ats.GREENHOUSE, "2")));
        when(lever.fetch(plaid)).thenThrow(new BoardNotFoundException(URI.create("https://api.lever.co/v0/postings/plaid")));
        when(ashby.fetch(ramp)).thenReturn(List.of(posting(Ats.ASHBY, "a")));

        IngestReport report = service.ingest(new IngestRequest(null, mode));

        assertThat(report.mode()).isEqualTo(mode);
        assertThat(report.startedAt()).isEqualTo(NOW);
        assertThat(report.companiesRequested()).isEqualTo(3);
        assertThat(report.companiesSucceeded()).isEqualTo(2);
        assertThat(report.postingsFetched()).isEqualTo(3);
        assertThat(report.postingsByAts()).containsEntry(Ats.GREENHOUSE, 2).containsEntry(Ats.ASHBY, 1)
                .doesNotContainKey(Ats.LEVER);
        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.boardToken()).isEqualTo("plaid");
            assertThat(f.errorType()).isEqualTo("BoardNotFoundException");
        });
        assertThat(report.wallTime()).isGreaterThanOrEqualTo(report.fetchTime());
        assertThat(report.sync().created()).isEqualTo(3);
        // The failed board must not be synced: an empty list would wrongly close all its postings.
        verify(store, never()).syncBoard(eq(plaid), any(), any());
        ScoreBreakdown half = scorer.score(null, profile);
        verify(store).syncBoard(stripe, List.of(new ScoredPosting(posting(Ats.GREENHOUSE, "1"), half),
                new ScoredPosting(posting(Ats.GREENHOUSE, "2"), half)), NOW);
        verify(runs).save(any(IngestRunEntity.class));
        assertThat(report.dedup()).isEqualTo(new IngestReport.DedupCounts(3, 0, 0));
    }

    @Test
    void unexpectedBugInOneSourceIsAlsoIsolated() {
        when(greenhouse.fetch(any())).thenThrow(new IllegalStateException("boom"));
        when(lever.fetch(any())).thenReturn(List.of());
        when(ashby.fetch(any())).thenReturn(List.of());

        IngestReport report = service.ingest(IngestRequest.all());

        assertThat(report.companiesSucceeded()).isEqualTo(2);
        assertThat(report.failures()).extracting(IngestReport.CompanyFailure::message).containsExactly("boom");
    }

    @Test
    void subsetOfCompaniesOnlyFetchesThose() {
        when(ashby.fetch(ramp)).thenReturn(List.of());

        IngestReport report = service.ingest(new IngestRequest(List.of("ramp", "ramp"), FetchMode.SEQUENTIAL));

        assertThat(report.companiesRequested()).isEqualTo(1);
        verify(greenhouse, never()).fetch(any());
        verify(lever, never()).fetch(any());
    }

    @Test
    void unknownCompanyIsRejectedBeforeAnyFetch() {
        assertThatThrownBy(() -> service.ingest(new IngestRequest(List.of("ramp", "nope"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
        verify(ashby, never()).fetch(any());
    }

    @Test
    void defaultsToVirtualThreadsAndAllCompanies() {
        IngestRequest request = IngestRequest.all();
        assertThat(request.mode()).isEqualTo(FetchMode.VIRTUAL);
        assertThat(request.companies()).isEmpty();
        assertThat(service.configuredCompanies()).containsExactly(stripe, plaid, ramp);
    }

    static Posting posting(Ats ats, String id) {
        return new Posting(ats, id, "Co", "Engineer", List.of(), null, null, "https://x.io/" + id, null, null,
                null, null);
    }
}
