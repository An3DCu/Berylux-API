# Database

PostgreSQL, changed only through Flyway migrations in [`src/main/resources/db/migration`](../src/main/resources/db/migration). The app keeps its own copy of the same data in Room and syncs it with these tables.

```mermaid
erDiagram
    users ||--o{ refresh_tokens : "signs in with"
    users ||--|| profiles : has
    users ||--o{ custom_challenges : writes
    users ||--o{ cases : takes
    users ||--o{ achievement_unlocks : earns
    users ||--o{ sync_changes : "is synced by"
    challenges ||--o{ cases : "is about"
    custom_challenges ||--o{ cases : "is about"

    users {
        uuid id PK
        text email UK
        text password_hash
        timestamptz created_at
    }
    refresh_tokens {
        uuid id PK
        uuid user_id FK
        uuid family_id
        text token_hash UK
        timestamptz created_at
        timestamptz expires_at
        timestamptz used_at
        timestamptz revoked_at
    }
    profiles {
        uuid user_id PK, FK
        text name
        bigint sigil_seed
        bigint pick_seed
        text time_zone
        smallint day_start_hour
        smallint next_day_start_hour
        timestamptz next_hour_from
        boolean on_leaderboard
        timestamptz updated_at
    }
    challenges {
        bigint id PK
        bigint category_id
        text title
        text description
        text difficulty
    }
    custom_challenges {
        uuid id PK
        uuid user_id FK
        text title
        text description
        timestamptz updated_at
        timestamptz deleted_at
    }
    cases {
        uuid id PK
        uuid user_id FK
        bigint challenge_id FK
        uuid custom_challenge_id FK
        text status
        boolean is_daily
        timestamptz accepted_at
        date accepted_on
        timestamptz closed_at
        date closed_on
        timestamptz updated_at
    }
    achievement_unlocks {
        uuid user_id PK, FK
        text key PK
        timestamptz unlocked_at
    }
    sync_changes {
        bigserial seq PK
        uuid user_id FK
        text entity
        text entity_id
        text op
        timestamptz changed_at
    }
```

## The rules in the schema

- **One profile per account.** Its time zone and the hour the day starts make game days on the server what they are on the phone. A new start hour waits in `next_day_start_hour` until `next_hour_from`, as in the app.
- **Ids a phone creates are UUIDs.** Cases and the player's own challenges get their id on the phone, offline, and the server keeps it.
- **Built-in challenges** come from the app's own `challenges.json`, copied to [`src/main/resources/seed`](../src/main/resources/seed) and loaded by the migration `V2__Load_challenges`, with the same ids. The server needs each one's difficulty to count Lux.
- **A case is about one challenge**, a built-in one or the player's own, never both. A closed case has the game day it was closed on, and only a closed case has one.
- **An own challenge that's deleted is kept** with `deleted_at`. That's how the deletion reaches the player's other phones.
- **Deleting an account** deletes everything that was theirs, through `on delete cascade`.
- **`sync_changes`** lists every change in order. A phone's cursor is the last `seq` it has seen.

## The weekly leaderboard

The view `weekly_leaderboard` gives the Lux each player earned in each week, from the cases they closed, for players who opted in. Weeks start on Monday and count game days, as on the app's Rivals board. Lux follows the app's rules: Easy 10, Medium 25, Hard 50; the daily case 1.5 times that with a half rounded up; the player's own challenges nothing. Drops don't take Lux off the board.
