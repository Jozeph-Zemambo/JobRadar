-- Workday job paths ("/job/Toronto/Engineer_R123") are only unique within one career site, but posting identity
-- is (ats, external_id). Workday ids now carry their board token as a prefix; existing rows are rewritten to
-- match so they keep their history instead of being closed and re-created.
alter table posting alter column external_id set data type varchar(400);
update posting set external_id = board_token || external_id
    where ats = 'WORKDAY' and external_id like '/%';
