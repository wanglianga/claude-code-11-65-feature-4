-- Backfill lab: feature dependency graph, historical availability scanner,
-- missing-data policy library, sharded backfill scheduler and the immutable
-- experiment snapshot repository.
--
-- Design rules encoded here:
--   * A planting declaration whose ingested_at is later than the case cutoff is a
--     look-ahead risk and must be flagged by the scanner, never silently adopted.
--   * Experiment snapshots are immutable. Changing a missing-data policy (for
--     example cloud occlusion from INTERPOLATE to EXCLUDE_MISSING) requires a new
--     experiment; already reported metrics can never be overwritten.
--   * Snapshots are keyed by (experiment, case, definition version) so two
--     definition versions backfilling the same case concurrently never share a row.

-- ---------------------------------------------------------------------------
-- Feature dependency graph: what each feature needs in order to be computable
-- for a historical case (sowing date, temperature series, imagery sequence...).
-- ---------------------------------------------------------------------------

create table feature_dependency (
    id                    bigserial primary key,
    feature_definition_id bigint not null references feature_definition(id),
    dep_key               varchar(64) not null,
    source_type           varchar(32) not null,
    required              boolean not null default true,
    params                jsonb not null default '{}'::jsonb,
    notes                 text,
    created_at            timestamptz not null default now(),
    unique (feature_definition_id, dep_key)
);

-- ---------------------------------------------------------------------------
-- Missing-data policy library. Policies are versioned; an experiment freezes
-- the exact policy versions it used so a strategy change always produces a new
-- experiment instead of mutating reported metrics.
-- ---------------------------------------------------------------------------

create table missing_policy (
    id          bigserial primary key,
    policy_code varchar(64) not null,        -- CLOUD_OCCLUSION | LATE_DECLARATION | MISSING_WEATHER
    version_no  int not null,
    action      varchar(40) not null,        -- INTERPOLATE | EXCLUDE_MISSING | FLAG_LOOKAHEAD_RISK
    params      jsonb not null default '{}'::jsonb,
    status      varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','RETIRED')),
    notes       text,
    created_at  timestamptz not null default now(),
    unique (policy_code, version_no)
);

-- ---------------------------------------------------------------------------
-- Historical availability scanner. One scan evaluates every historical case
-- against the dependency graph of one feature, as of that case's own cutoff.
-- ---------------------------------------------------------------------------

create table availability_scan (
    id                    bigserial primary key,
    feature_definition_id bigint not null references feature_definition(id),
    definition_version_id bigint references feature_definition_version(id),
    hazard_from           date,
    hazard_to             date,
    status                varchar(16) not null default 'COMPLETED'
                              check (status in ('RUNNING','COMPLETED','FAILED')),
    case_count            int not null default 0,
    evaluable_count       int not null default 0,
    post_hoc_count        int not null default 0,
    summary               jsonb,
    created_by            varchar(120),
    created_at            timestamptz not null default now()
);

create table availability_scan_item (
    id             bigserial primary key,
    scan_id        bigint not null references availability_scan(id) on delete cascade,
    case_id        bigint not null references claim_case(id),
    feature_code   varchar(64) not null,
    feature_status varchar(24) not null
                       check (feature_status in ('AVAILABLE','DEGRADED','LOOKAHEAD_RISK','MISSING')),
    case_class     varchar(24) not null check (case_class in ('EVALUABLE','POST_HOC_ONLY')),
    dependencies   jsonb not null,         -- per-dependency verdicts with evidence
    reasons        jsonb not null,         -- human-readable missing / look-ahead reasons
    unique (scan_id, case_id)
);

create index idx_scan_item_scan on availability_scan_item(scan_id, case_class);

-- ---------------------------------------------------------------------------
-- Experiments. An experiment freezes (feature, policy set, case scope, scan);
-- every metric derived from it stays comparable. A policy change must create a
-- new experiment key rather than reuse an old one.
-- ---------------------------------------------------------------------------

create table experiment (
    id              bigserial primary key,
    experiment_key  varchar(120) not null unique,
    feature_code    varchar(64) not null,
    policy_set      jsonb not null,          -- {POLICY_CODE: {policyId, versionNo, action}}
    case_scope      varchar(24) not null default 'EVALUABLE_ONLY'
                        check (case_scope in ('EVALUABLE_ONLY','INCLUDE_POST_HOC')),
    scan_id         bigint references availability_scan(id),
    status          varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE','ARCHIVED')),
    notes           text,
    created_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Backfill scheduler. A job belongs to exactly one experiment and is sharded
-- by (definition version, case): the scheduler fans out one shard per
-- definition version per scanned case.
-- ---------------------------------------------------------------------------

create table backfill_job (
    id               bigserial primary key,
    job_code         varchar(120) not null unique,
    experiment_id    bigint not null references experiment(id),
    status           varchar(24) not null default 'QUEUED'
                         check (status in ('QUEUED','RUNNING','COMPLETED','COMPLETED_WITH_MISSING','FAILED')),
    total_shards     int not null default 0,
    computed_shards  int not null default 0,
    missing_shards   int not null default 0,
    skipped_shards   int not null default 0,
    failed_shards    int not null default 0,
    requested_by     varchar(120),
    created_at       timestamptz not null default now(),
    started_at       timestamptz,
    finished_at      timestamptz
);

create table backfill_shard (
    id                     bigserial primary key,
    job_id                 bigint not null references backfill_job(id) on delete cascade,
    shard_no               int not null,
    case_id                bigint not null references claim_case(id),
    definition_version_id  bigint not null references feature_definition_version(id),
    case_class             varchar(24) not null check (case_class in ('EVALUABLE','POST_HOC_ONLY')),
    status                 varchar(24) not null default 'QUEUED'
                               check (status in ('QUEUED','RUNNING','COMPUTED','MISSING','SKIPPED_SCOPE','FAILED')),
    missing_reason         text,
    experiment_snapshot_id bigint,
    error_message          text,
    started_at             timestamptz,
    finished_at            timestamptz,
    unique (job_id, case_id, definition_version_id)
);

create index idx_shard_job on backfill_shard(job_id, status);

-- ---------------------------------------------------------------------------
-- Experiment snapshot repository. Each row is the frozen result of one shard:
-- inputs, outputs and missing reasons, keyed by (experiment, case, definition
-- version) so concurrent definition versions never contaminate each other.
-- ---------------------------------------------------------------------------

create table experiment_snapshot (
    id                    bigserial primary key,
    experiment_id         bigint not null references experiment(id),
    shard_id              bigint not null references backfill_shard(id),
    case_id               bigint not null references claim_case(id),
    feature_code          varchar(64) not null,
    definition_version_id bigint not null references feature_definition_version(id),
    case_class            varchar(24) not null check (case_class in ('EVALUABLE','POST_HOC_ONLY')),
    cutoff_at             timestamptz not null,
    availability_ceiling  timestamptz not null,
    value                 numeric(18,6),
    value_status          varchar(24) not null
                              check (value_status in ('COMPUTED','MISSING_EXCLUDED','INSUFFICIENT_DATA')),
    missing_reasons       jsonb not null default '[]'::jsonb,
    policy_applications   jsonb not null default '[]'::jsonb,
    inputs                jsonb not null,
    outputs               jsonb not null,
    inputs_hash           varchar(64) not null,
    created_at            timestamptz not null default now(),
    unique (experiment_id, case_id, definition_version_id)
);

create index idx_experiment_snapshot_exp on experiment_snapshot(experiment_id, case_class, value_status);
create index idx_experiment_snapshot_case on experiment_snapshot(case_id);

-- Experiment snapshots are append-only evidence, same as scoring snapshots.
create or replace function block_experiment_snapshot_mutation() returns trigger
language plpgsql as $$
begin
    raise exception 'experiment snapshots are immutable (% on %); create a new experiment instead', tg_op, tg_table_name;
end;
$$;

create trigger trg_experiment_snapshot_immutable
    before update or delete on experiment_snapshot
    for each row execute function block_experiment_snapshot_mutation();
