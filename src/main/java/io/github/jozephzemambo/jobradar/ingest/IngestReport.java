package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.persistence.SyncCounts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Summary of one ingest run, returned by {@code POST /api/ingest} and used by the benchmark harness.
 *
 * @param startedAt          when the run began
 * @param mode               fetch strategy used
 * @param companiesRequested boards attempted
 * @param companiesSucceeded boards read successfully (including empty ones)
 * @param failures           boards that failed, with the reason
 * @param postingsFetched    postings returned by all boards before dedup
 * @param postingsByAts      postingsFetched split by ATS
 * @param sync               what the run did to stored postings (new / still listed / reopened / closed)
 * @param fetchTime          wall time of the fetch phase (HTTP + parse + map)
 * @param persistTime        wall time of the database phase
 * @param wallTime           wall time of the whole run
 * @param runId              id of the stored ingest_run row
 */
public record IngestReport(
        Long runId,
        Instant startedAt,
        FetchMode mode,
        int companiesRequested,
        int companiesSucceeded,
        List<CompanyFailure> failures,
        int postingsFetched,
        Map<Ats, Integer> postingsByAts,
        SyncCounts sync,
        Duration fetchTime,
        Duration persistTime,
        Duration wallTime) {

    public record CompanyFailure(String company, String boardToken, Ats ats, String errorType, String message) {
    }
}
