-- H2 has no partial indexes; the same ordering as a full index keeps the two databases' plans comparable.
create index idx_posting_ranked on posting (score desc nulls last, first_seen_at desc, id);
