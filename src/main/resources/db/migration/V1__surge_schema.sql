create table events (
    id          bigint generated always as identity primary key,
    name        text        not null,
    capacity    int         not null check (capacity > 0),
    created_at  timestamptz not null default now()
);

-- Postgres is the source of truth. The check constraint is the last line of
-- defence: even if every layer above it were wrong, sold can never exceed capacity.
create table inventory (
    event_id  bigint primary key references events (id),
    capacity  int not null,
    sold      int not null default 0,
    constraint no_oversell check (sold <= capacity)
);

create table holds (
    id          uuid primary key,
    event_id    bigint      not null references events (id),
    user_id     text        not null,
    quantity    int         not null check (quantity between 1 and 10),
    status      text        not null check (status in ('HELD', 'PURCHASED', 'EXPIRED')),
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index holds_expiry_idx on holds (expires_at) where status = 'HELD';
create index holds_event_idx on holds (event_id, status);

create table orders (
    id          uuid primary key,
    hold_id     uuid        not null unique references holds (id),
    event_id    bigint      not null references events (id),
    user_id     text        not null,
    quantity    int         not null,
    created_at  timestamptz not null default now()
);
