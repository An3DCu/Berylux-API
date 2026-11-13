-- Accounts, their one profile, and everything a phone syncs. Ids a phone creates (cases, own
-- challenges) are UUIDs made on the phone; built-in challenges keep the app's seed ids.

create table users (
    id            uuid primary key,
    email         text        not null unique,
    password_hash text        not null,
    created_at    timestamptz not null default now()
);

create table refresh_tokens (
    id         uuid primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    token_hash text        not null unique,
    expires_at timestamptz not null,
    revoked_at timestamptz
);

create index refresh_tokens_user on refresh_tokens (user_id);

-- One per account. The time zone and the start hour make game days on the server what they are
-- on the phone; a new start hour waits for next_hour_from, as in the app.
create table profiles (
    user_id             uuid primary key references users (id) on delete cascade,
    name                text        not null,
    sigil_seed          bigint      not null,
    pick_seed           bigint      not null,
    time_zone           text        not null,
    day_start_hour      smallint    not null default 4 check (day_start_hour between 0 and 23),
    next_day_start_hour smallint check (next_day_start_hour between 0 and 23),
    next_hour_from      timestamptz,
    on_leaderboard      boolean     not null default false,
    updated_at          timestamptz not null default now()
);

-- The app's built-in challenges, loaded from the same challenges.json (V2). The server needs each
-- one's difficulty to count Lux.
create table challenges (
    id          bigint primary key,
    category_id bigint not null,
    title       text   not null,
    description text   not null,
    difficulty  text   not null check (difficulty in ('EASY', 'MEDIUM', 'HARD'))
);

-- A deleted one is kept with deleted_at, so the deletion reaches the player's other phones.
create table custom_challenges (
    id          uuid primary key,
    user_id     uuid        not null references users (id) on delete cascade,
    title       text        not null,
    description text        not null default '',
    updated_at  timestamptz not null,
    deleted_at  timestamptz
);

create index custom_challenges_user on custom_challenges (user_id);

-- A case is about a built-in challenge or one of the player's own, never both. accepted_on and
-- closed_on are game days.
create table cases (
    id                  uuid primary key,
    user_id             uuid        not null references users (id) on delete cascade,
    challenge_id        bigint references challenges (id),
    custom_challenge_id uuid references custom_challenges (id) on delete cascade,
    status              text        not null check (status in ('OPEN', 'CLOSED', 'DROPPED', 'COLD')),
    is_daily            boolean     not null,
    accepted_at         timestamptz not null,
    accepted_on         date        not null,
    closed_at           timestamptz,
    closed_on           date,
    updated_at          timestamptz not null,
    constraint cases_one_challenge check ((challenge_id is null) <> (custom_challenge_id is null)),
    constraint cases_closed_has_day check ((status = 'CLOSED') = (closed_on is not null))
);

create index cases_user_closed_on on cases (user_id, closed_on);

-- Once unlocked, a commendation stays: the first time is kept.
create table achievement_unlocks (
    user_id     uuid        not null references users (id) on delete cascade,
    key         text        not null,
    unlocked_at timestamptz not null,
    primary key (user_id, key)
);

-- Every change a phone has to pull, in order. A phone's cursor is the last seq it has seen.
create table sync_changes (
    seq        bigserial primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    entity     text        not null,
    entity_id  text        not null,
    op         text        not null check (op in ('UPSERT', 'DELETE')),
    changed_at timestamptz not null default now()
);

create index sync_changes_user_seq on sync_changes (user_id, seq);
