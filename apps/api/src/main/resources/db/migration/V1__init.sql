create table if not exists business_domain (
    id varchar(64) primary key,
    name varchar(255) not null,
    description text
);

create table if not exists scenario (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    goal text not null,
    version varchar(32) not null,
    version_status varchar(32) not null
);

create table if not exists agent_group (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    name varchar(255) not null,
    description text
);

create table if not exists agent (
    id varchar(64) primary key,
    agent_group_id varchar(64) not null,
    name varchar(255) not null,
    role varchar(64) not null,
    responsibility text
);

create table if not exists resource (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    type varchar(32) not null,
    share_scope varchar(32) not null,
    owner_type varchar(32) not null,
    owner_id varchar(64) not null,
    summary text
);

create table if not exists resource_binding (
    id varchar(64) primary key,
    resource_id varchar(64) not null,
    consumer_type varchar(32) not null,
    consumer_id varchar(64) not null,
    created_at timestamp not null default current_timestamp
);

create table if not exists task_instance (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    question text not null,
    customer_id varchar(255) not null,
    status varchar(32) not null,
    workflow_instance_id varchar(64) not null,
    created_at timestamp not null default current_timestamp
);

create table if not exists workflow_instance (
    id varchar(64) primary key,
    task_id varchar(64) not null,
    status varchar(32) not null,
    summary text,
    escalation_required boolean not null default false
);
