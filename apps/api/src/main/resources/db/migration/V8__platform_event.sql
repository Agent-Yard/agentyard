create table platform_event (
    id varchar(64) primary key,
    event_type varchar(128) not null,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(64) not null,
    actor_id varchar(255),
    payload jsonb not null,
    occurred_at timestamp with time zone not null
);

create index idx_platform_event_aggregate_occurred
    on platform_event (aggregate_type, aggregate_id, occurred_at desc, id desc);

create index idx_platform_event_occurred
    on platform_event (occurred_at desc, id desc);
