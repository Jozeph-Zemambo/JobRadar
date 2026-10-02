package io.github.jozephzemambo.jobradar.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.persistence.SyncCounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

class CrawlRunnerTest {

    private final IngestService ingest = mock(IngestService.class);
    private final CrawlRunner runner = new CrawlRunner(ingest, JsonMapper.builder().build());

    @Test
    void exitsZeroWhenAnyBoardSucceededEvenIfSomeFailed() {
        when(ingest.ingest(any())).thenReturn(report(3, List.of(
                new IngestReport.CompanyFailure("Plaid", "plaid", Ats.LEVER, "BoardNotFoundException", "gone"))));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
        verify(ingest).ingest(IngestRequest.all());
    }

    @Test
    void exitsOneWhenEveryBoardFailed() {
        when(ingest.ingest(any())).thenReturn(report(0, List.of()));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void exitsOneWhenIngestThrows() {
        when(ingest.ingest(any())).thenThrow(new IllegalStateException("db down"));

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void scheduledCrawlSkipsWhenAnotherIngestIsRunning() {
        when(ingest.ingest(any())).thenThrow(new IngestInProgressException()).thenReturn(report(1, List.of()));
        ScheduledIngest scheduled = new ScheduledIngest(ingest);

        scheduled.crawl(); // must not throw
        scheduled.crawl();

        verify(ingest, org.mockito.Mockito.times(2)).ingest(IngestRequest.all());
    }

    private static IngestReport report(int succeeded, List<IngestReport.CompanyFailure> failures) {
        return new IngestReport(1L, Instant.EPOCH, FetchMode.VIRTUAL, succeeded + failures.size(), succeeded,
                failures, 0, Map.of(), SyncCounts.ZERO, new IngestReport.DedupCounts(0, 0, 0), 0, 0, 0);
    }
}
