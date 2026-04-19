create table if not exists catalog_playbook (
    id varchar(64) primary key,
    payload jsonb not null
);
