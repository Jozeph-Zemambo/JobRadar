-- Duplicates are linked to the posting they duplicate, never deleted, so a wrong dedup decision is
-- reversible and auditable. Recomputed over open postings after every ingest.
alter table posting add column duplicate_of_id bigint;
alter table posting add column dedup_reason varchar(20);
alter table posting add constraint fk_posting_duplicate_of foreign key (duplicate_of_id) references posting (id);
create index idx_posting_duplicate_of on posting (duplicate_of_id);
