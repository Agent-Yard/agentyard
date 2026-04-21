create table if not exists shared_state_revision (
    domain varchar(64) primary key,
    revision bigint not null,
    updated_at timestamp with time zone not null
);

insert into shared_state_revision (domain, revision, updated_at)
values
    ('catalog', 0, now()),
    ('knowledge', 0, now())
on conflict (domain) do nothing;
