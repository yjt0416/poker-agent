create table table_sessions (
    token_hash varchar(64) primary key,
    tournament_id uuid not null references tournaments(tournament_id),
    sequence bigint not null check (sequence > 0),
    metadata jsonb not null,
    expires_at timestamptz not null
);
create index table_sessions_expiry on table_sessions(expires_at);
create table table_frames (
    token_hash varchar(64) not null references table_sessions(token_hash) on delete cascade,
    sequence bigint not null check (sequence > 0),
    projection jsonb not null,
    primary key(token_hash, sequence)
);
