create table web_push_subscription (
    id uuid primary key,
    recipient_id varchar(255) not null references member(id) on delete cascade,
    endpoint varchar(2048) not null unique,
    p256dh varchar(87) not null,
    auth varchar(22) not null,
    expiration_time bigint check (expiration_time >= 0)
);

create index web_push_subscription_recipient_idx on web_push_subscription (recipient_id);
