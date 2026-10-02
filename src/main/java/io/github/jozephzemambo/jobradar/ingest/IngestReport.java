package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.persistence.SyncCounts;
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
 * @param failures           boards that failed (fetch or storage), with the reason
 * @param incompleteBoards   boards read but possibly not in full; nothing was marked closed for them this run
 * @param postingsFetched    postings returned by all boards before dedup
 * @param postingsByAts      postingsFetched split by ATS
 * @param sync               what the run did to stored postings (new / still listed / reopened / closed)
 * @param dedup              duplicate counts across all open postings after this run
 * @param fetchMillis        wall time of the fetch phase (HTTP + parse + map)
 * @param persistMillis      wall time of scoring + database + dedup
 * @param wallMillis         wall time of the whole run
 * @param runId              id of the stored ingest_run row
 */
public record IngestReport(
        Long runId,
        Instant startedAt,
        FetchMode mode,
        int companiesRequested,
        int companiesSucceeded,
        List<CompanyFailure> failures,
        List<IncompleteBoard> incompleteBoards,
        int postingsFetched,
        Map<Ats, Integer> postingsByAts,
        SyncCounts sync,
        DedupCounts dedup,
        long fetchMillis,
        long persistMillis,
        long wallMillis) {

    /**
     * @param openPostings    open postings considered
     * @param exactDuplicates duplicates by identical canonical URL
     * @param fuzzyDuplicates duplicates by the fuzzy rule
     */
    public record DedupCounts(int openPostings, long exactDuplicates, long fuzzyDuplicates) {
    }

    public record IncompleteBoard(String boardToken, Ats ats, String reason) {
    }

    public record CompanyFailure(String company, String boardToken, Ats ats, String errorType, String message) {
    }
}
