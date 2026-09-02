create table tournaments (
  tournament_id uuid primary key,
  mode varchar(24) not null,
  status varchar(24) not null,
  version bigint not null check (version >= 1),
  last_sequence bigint not null check (last_sequence >= 0),
  button_seat smallint not null check (button_seat between 0 and 5),
  completed_hands integer not null check (completed_hands >= 0),
  blind_level_index smallint not null check (blind_level_index between 0 and 14),
  updated_at timestamptz not null
);

create table tournament_seats (
  tournament_id uuid not null references tournaments(tournament_id) on delete cascade,
  seat_index smallint not null check (seat_index between 0 and 5),
  player_id uuid not null,
  stack bigint not null check (stack >= 0),
  status varchar(24) not null,
  finish_position smallint check (finish_position between 1 and 6),
  current_hand_starting_stack bigint check (current_hand_starting_stack >= 0),
  primary key (tournament_id, seat_index),
  unique (tournament_id, player_id)
);

create table hand_snapshots (
  tournament_id uuid primary key references tournaments(tournament_id) on delete cascade,
  hand_id uuid not null unique,
  checkpoint_json jsonb not null
);

create table tournament_events (
  tournament_event_id uuid primary key,
  tournament_id uuid not null references tournaments(tournament_id) on delete cascade,
  sequence bigint not null check (sequence > 0),
  aggregate_version bigint not null check (aggregate_version >= 1),
  occurred_at timestamptz not null,
  occurred_epoch_second bigint not null,
  occurred_nano integer not null check (occurred_nano between 0 and 999999999),
  event_type varchar(64) not null,
  payload_json jsonb not null,
  hand_id uuid,
  unique (tournament_id, sequence)
);

create table tournament_command_receipts (
  receipt_id uuid primary key,
  tournament_id uuid not null references tournaments(tournament_id) on delete cascade,
  command_id uuid not null,
  aggregate_version bigint not null check (aggregate_version >= 1),
  last_sequence bigint not null check (last_sequence >= 0),
  event_sequence_start bigint not null check (event_sequence_start >= 0),
  event_sequence_end bigint not null check (event_sequence_end >= 0),
  event_count integer not null check (event_count >= 0),
  receipt_checkpoint_json jsonb not null,
  created_at timestamptz not null,
  unique (tournament_id, command_id),
  check (
    (event_count = 0 and event_sequence_start = event_sequence_end)
    or (
      event_count > 0
      and event_sequence_start > 0
      and event_sequence_end >= event_sequence_start
      and event_sequence_end - event_sequence_start + 1 = event_count
    )
  )
);

create index tournament_events_tournament_occurred_at_idx
  on tournament_events (tournament_id, occurred_at);

create index tournament_command_receipts_tournament_created_at_idx
  on tournament_command_receipts (tournament_id, created_at);
