package io.github.jozephzemambo.jobradar.stats;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Market snapshot returned by {@code GET /api/stats}. "Open" counts exclude postings linked as duplicates.
 *
 * @param openPostings     open, non-duplicate postings
 * @param closedPostings   postings that disappeared from their board
 * @param openDuplicates   open postings linked to another as duplicates
 * @param openByAts        open postings per ATS
 * @param openByWorkplace  open postings per workplace type
 * @param topCompanies     companies with the most open postings
 * @param topSkills        most-requested skills across open postings
 * @param timeToClose      how long observed postings stayed open
 * @param newLast7Days     postings first seen in the last 7 days (excluding each board's first-sync backlog)
 * @param crawl            crawl history summary
 */
public record StatsView(
        long openPostings,
        long closedPostings,
        long openDuplicates,
        Map<String, Long> openByAts,
        Map<String, Long> openByWorkplace,
        List<Count> topCompanies,
        List<SkillDemand> topSkills,
        TimeToClose timeToClose,
        long newLast7Days,
        Crawl crawl) {

    public record Count(String name, long count) {
    }

    /**
     * @param skill    canonical skill name
     * @param category dictionary category
     * @param postings open postings mentioning it
     * @param share    postings / all open postings
     */
    public record SkillDemand(String skill, String category, long postings, double share) {
    }

    /**
     * Time from first seen to closed, only for postings whose opening was observed (first seen after their board's
     * first sync). Postings already open when a board was first synced have an unknown start, so including them
     * would understate how long postings stay up. Null fields mean there isn't enough history yet.
     *
     * @param observedClosures closed postings that qualify
     * @param medianDays       median days open
     * @param p75Days          75th percentile days open
     */
    public record TimeToClose(long observedClosures, Double medianDays, Double p75Days) {
    }

    /**
     * @param runs          ingest runs recorded
     * @param firstRunAt    when tracking began
     * @param lastRunAt     most recent run
     * @param lastRunBoards boards attempted in the most recent run
     */
    public record Crawl(long runs, Instant firstRunAt, Instant lastRunAt, Integer lastRunBoards) {
    }
}
