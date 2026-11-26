create table notification_outbox (
    event_id uuid primary key,
    routing_key varchar(100) not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    next_attempt_at timestamptz not null default now(),
    attempts integer not null default 0,
    published_at timestamptz
);

create index notification_outbox_pending_idx
    on notification_outbox (next_attempt_at, created_at, event_id)
    where published_at is null;

create table notification (
    id uuid primary key,
    event_id uuid not null,
    recipient_id varchar(255) not null references member(id) on delete cascade,
    type varchar(100) not null,
    title varchar(255) not null,
    message text not null,
    target_path varchar(255) not null,
    resource_id varchar(255) not null,
    created_at timestamptz not null default now(),
    read_at timestamptz,
    unique (event_id, recipient_id)
);

create index notification_recipient_created_idx
    on notification (recipient_id, created_at desc, id desc);

create index notification_recipient_unread_idx
    on notification (recipient_id) where read_at is null;
