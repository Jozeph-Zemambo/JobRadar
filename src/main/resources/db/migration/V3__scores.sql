-- Ranking output and the skills each posting mentions (the latter feeds skill-demand statistics).
alter table posting add column score double precision;
alter table posting add column skills varchar(2000);
alter table posting add column matched_skills varchar(2000);
create index idx_posting_score on posting (score);
