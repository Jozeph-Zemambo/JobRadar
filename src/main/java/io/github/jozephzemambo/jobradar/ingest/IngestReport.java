package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Ats;
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
 * @param fetchTime          wall time of the fetch phase (HTTP + parse + map)
 * @param wallTime           wall time of the whole run
 */
public record IngestReport(
        Instant startedAt,
        FetchMode mode,
        int companiesRequested,
        int companiesSucceeded,
        List<CompanyFailure> failures,
        int postingsFetched,
        Map<Ats, Integer> postingsByAts,
        Duration fetchTime,
        Duration wallTime) {

    public record CompanyFailure(String company, String boardToken, Ats ats, String errorType, String message) {
    }
}
