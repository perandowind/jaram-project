alter table seminar
    add column creation_notification_recorded boolean not null default false,
    add column reminder_due_at timestamp(6) with time zone,
    add column start_notification_due_at timestamp(6) with time zone,
    add column reminder_recorded boolean not null default false,
    add column start_notification_recorded boolean not null default false;

create index seminar_reminder_due_idx on seminar (reminder_due_at) where reminder_due_at is not null;
create index seminar_start_notification_due_idx on seminar (start_notification_due_at)
    where start_notification_due_at is not null;
