-- Serves GET /api/postings' default query (open, non-duplicate, highest score first) straight from an index.
-- Without it Postgres scanned all ~11k rows and heap-sorted them on every request; at ~200 req/s that alone
-- saturated a 2-vCPU database. Partial: only rows the default listing can return are indexed.
create index idx_posting_ranked on posting (score desc nulls last, first_seen_at desc, id)
    where closed_at is null and duplicate_of_id is null;
