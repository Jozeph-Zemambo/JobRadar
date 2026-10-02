-- Postings and their lifecycle. A posting is identified by (ats, external_id); every ingest updates
-- last_seen_at for postings still on the board and sets closed_at for postings that disappeared.
-- Age and time-to-close are computed from first_seen_at/closed_at (our own observations), never from
-- source_published_at, which can be years stale on evergreen reqs.

-- Sequence (not identity) ids so Hibernate can batch inserts; allocation size matches the entity.
create sequence posting_seq start with 1 increment by 50;

create table posting (
    id                  bigint        not null primary key,
    ats                 varchar(20)   not null,
    external_id         varchar(200)  not null,
    board_token         varchar(100)  not null,
    company             varchar(200)  not null,
    title               varchar(500)  not null,
    normalized_title    varchar(500)  not null,
    locations           varchar(4000),
    workplace_type      varchar(20)   not null,
    department          varchar(500),
    url                 varchar(2000) not null,
    canonical_url       varchar(2000) not null,
    description         varchar(200000),
    compensation        varchar(500),
    source_published_at timestamp with time zone,
    first_seen_at       timestamp with time zone not null,
    last_seen_at        timestamp with time zone not null,
    closed_at           timestamp with time zone,
    constraint uk_posting_ats_external unique (ats, external_id)
);

create index idx_posting_board on posting (ats, board_token);
create index idx_posting_first_seen on posting (first_seen_at);
create index idx_posting_closed on posting (closed_at);

create sequence ingest_run_seq start with 1 increment by 1;

create table ingest_run (
    id                  bigint       not null primary key,
    started_at          timestamp with time zone not null,
    mode                varchar(20)  not null,
    companies_requested integer      not null,
    companies_succeeded integer      not null,
    postings_fetched    integer      not null,
    postings_new        integer      not null,
    postings_updated    integer      not null,
    postings_reopened   integer      not null,
    postings_closed     integer      not null,
    fetch_time_ms       bigint       not null,
    persist_time_ms     bigint       not null,
    wall_time_ms        bigint       not null
);
