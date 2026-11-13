-- Refresh tokens come in chains. Each refresh marks the old token used and adds a new one to the
-- same family. A used token that comes back means it was copied, and the whole family is revoked.
alter table refresh_tokens
    add column family_id  uuid        not null,
    add column created_at timestamptz not null default now(),
    add column used_at    timestamptz;

create index refresh_tokens_family on refresh_tokens (family_id);
