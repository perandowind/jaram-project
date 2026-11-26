-- Initial schema matching the current JPA mappings (PostgreSQL 16).
-- Existing databases must be reviewed and explicitly baselined at version 1.
-- Historical manual data backfills are not replayed here.

create table admin_settings (
    auto_promote boolean not null,
    cohort_set_on date,
    current_cohort integer,
    drive_connected boolean not null,
    last_rollover_term integer,
    last_rollover_year integer,
    semester_term integer,
    semester_term_set_on date,
    drive_folder varchar(255),
    id varchar(255) not null,
    link_blog varchar(255),
    link_discord varchar(255),
    link_github varchar(255),
    link_instagram varchar(255),
    primary key (id)
);

create table attendance (
    at timestamp(6) with time zone,
    id varchar(255) not null,
    member_id varchar(255),
    seminar_id varchar(255),
    primary key (id),
    unique (seminar_id, member_id)
);

create table member (
    contributor boolean default false not null,
    gen integer,
    grad_year integer,
    created_at timestamp(6) with time zone,
    credentials_invalidated_at timestamp(6) with time zone,
    purged_at timestamp(6) with time zone,
    reregister_requested_at timestamp(6) with time zone,
    version bigint,
    withdrawn_at timestamp(6) with time zone,
    bio varchar(1000),
    approval varchar(255) check (approval in ('PENDING','APPROVED','REJECTED')),
    blog_url varchar(255),
    email varchar(255),
    faculty varchar(255),
    github_url varchar(255),
    grade varchar(255) check (grade in ('NEWCOMER','ASSOCIATE','REGULAR','OB')),
    id varchar(255) not null,
    name varchar(255),
    password_hash varchar(255),
    phone varchar(255),
    status varchar(255) check (status in ('ACTIVE','ON_LEAVE','REREGISTER','WITHDRAWN')),
    student_id varchar(255),
    primary key (id),
    unique (email),
    unique (student_id)
);

create table member_career (
    at_text varchar(255),
    id varchar(255) not null,
    job varchar(255),
    member_id varchar(255) not null,
    org varchar(255),
    primary key (id)
);

create table member_term (
    end_gen integer,
    start_gen integer not null,
    department varchar(255) not null check (department in ('LEADERSHIP','ACADEMIC','PR','FINANCE','INFRA')),
    id varchar(255) not null,
    member_id varchar(255) not null,
    title varchar(255) not null check (title in ('PRESIDENT','VICE_PRESIDENT','LEAD','STAFF','SERVER_ADMIN')),
    primary key (id)
);

create table password_reset_token (
    expires_at timestamp(6) with time zone,
    used_at timestamp(6) with time zone,
    id varchar(255) not null,
    member_id varchar(255),
    token varchar(255),
    primary key (id),
    unique (token)
);

create table schedule (
    capacity integer,
    starts_at timestamp(6) with time zone,
    version bigint,
    id varchar(255) not null,
    mode varchar(255),
    place varchar(255),
    status varchar(255) check (status in ('OPEN','LOCKED')),
    primary key (id)
);

create table schedule_slot (
    slot_index integer,
    id varchar(255) not null,
    member_id varchar(255),
    schedule_id varchar(255),
    seminar_id varchar(255),
    primary key (id)
);

create table seminar (
    capacity integer,
    attendance_closed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    starts_at timestamp(6) with time zone,
    version bigint,
    reject_reason varchar(1000),
    approval_status varchar(255) check (approval_status in ('PENDING','APPROVED','REJECTED')),
    attendance_code varchar(255),
    created_by_id varchar(255),
    description varchar(255),
    id varchar(255) not null,
    material_url varchar(255),
    mode varchar(255),
    place varchar(255),
    schedule_id varchar(255),
    speaker varchar(255),
    title varchar(255),
    topic varchar(255),
    primary key (id)
);

create table study (
    capacity integer,
    created_at timestamp(6) with time zone,
    version bigint,
    reason varchar(1000),
    intro varchar(2000),
    contact varchar(255),
    id varchar(255) not null,
    leader_id varchar(255),
    mode varchar(255),
    place varchar(255),
    schedule varchar(255),
    status varchar(255) check (status in ('PENDING','REJECTED','RECRUITING','ONGOING','FINISHED')),
    title varchar(255),
    primary key (id)
);

create table study_application (
    created_at timestamp(6) with time zone,
    reason varchar(1000),
    motive varchar(2000),
    applicant_id varchar(255),
    id varchar(255) not null,
    status varchar(255) check (status in ('PENDING','APPROVED','REJECTED')),
    study_id varchar(255),
    primary key (id),
    unique (study_id, applicant_id)
);

create table study_attendance (
    at timestamp(6) with time zone,
    id varchar(255) not null,
    member_id varchar(255),
    week_id varchar(255),
    primary key (id),
    unique (week_id, member_id)
);

create table study_field (
    field varchar(255),
    study_id varchar(255) not null
);

create table study_recruitment (
    is_open boolean,
    id varchar(255) not null,
    primary key (id)
);

create table study_week (
    week_no integer,
    taken_at timestamp(6) with time zone,
    content varchar(2000),
    id varchar(255) not null,
    study_id varchar(255),
    title varchar(255),
    primary key (id),
    unique (study_id, week_no)
);

alter table if exists member_career add constraint FKaji5ak2hiwpkttmnrq2f73015 foreign key (member_id) references member;

alter table if exists member_term add constraint FKrtih56dvkore774vnao4lglic foreign key (member_id) references member;

alter table if exists schedule_slot add constraint FKsf6gb5d8qjf82rivclbixrnve foreign key (schedule_id) references schedule;

alter table if exists study_field add constraint FK1yhg51534hbmn94odj55x5x4n foreign key (study_id) references study;
