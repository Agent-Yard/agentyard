create table if not exists catalog_domain (
    id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_scenario (
    id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_assistant (
    id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_agent (
    id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_resource (
    id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_resource_versions (
    resource_id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_assistant_releases (
    assistant_id varchar(64) primary key,
    payload jsonb not null
);

create table if not exists catalog_orchestration (
    assistant_id varchar(64) primary key,
    payload jsonb not null
);
