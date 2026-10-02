package io.github.jozephzemambo.jobradar.persistence;

import io.github.jozephzemambo.jobradar.ingest.FetchMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row per ingest, so crawl history (and benchmark runs) can be audited later. */
@Entity
@Table(name = "ingest_run")
public class IngestRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ingest_run_seq")
    @SequenceGenerator(name = "ingest_run_seq", sequenceName = "ingest_run_seq", allocationSize = 1)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FetchMode mode;

    @Column(name = "companies_requested", nullable = false)
    private int companiesRequested;

    @Column(name = "companies_succeeded", nullable = false)
    private int companiesSucceeded;

    @Column(name = "postings_fetched", nullable = false)
    private int postingsFetched;

    @Column(name = "postings_new", nullable = false)
    private int postingsNew;

    @Column(name = "postings_updated", nullable = false)
    private int postingsUpdated;

    @Column(name = "postings_reopened", nullable = false)
    private int postingsReopened;

    @Column(name = "postings_closed", nullable = false)
    private int postingsClosed;

    @Column(name = "fetch_time_ms", nullable = false)
    private long fetchTimeMs;

    @Column(name = "persist_time_ms", nullable = false)
    private long persistTimeMs;

    @Column(name = "wall_time_ms", nullable = false)
    private long wallTimeMs;

    protected IngestRunEntity() {
        // for JPA
    }

    public IngestRunEntity(Instant startedAt, FetchMode mode, int companiesRequested, int companiesSucceeded,
            int postingsFetched, SyncCounts counts, long fetchTimeMs, long persistTimeMs, long wallTimeMs) {
        this.startedAt = startedAt;
        this.mode = mode;
        this.companiesRequested = companiesRequested;
        this.companiesSucceeded = companiesSucceeded;
        this.postingsFetched = postingsFetched;
        this.postingsNew = counts.created();
        this.postingsUpdated = counts.updated();
        this.postingsReopened = counts.reopened();
        this.postingsClosed = counts.closed();
        this.fetchTimeMs = fetchTimeMs;
        this.persistTimeMs = persistTimeMs;
        this.wallTimeMs = wallTimeMs;
    }

    public Long getId() {
        return id;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public FetchMode getMode() {
        return mode;
    }

    public int getCompaniesSucceeded() {
        return companiesSucceeded;
    }

    public int getPostingsFetched() {
        return postingsFetched;
    }

    public long getWallTimeMs() {
        return wallTimeMs;
    }
}
