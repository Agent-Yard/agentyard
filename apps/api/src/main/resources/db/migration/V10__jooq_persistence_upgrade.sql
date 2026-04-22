drop table if exists catalog_domain cascade;
drop table if exists catalog_scenario cascade;
drop table if exists catalog_assistant cascade;
drop table if exists catalog_agent cascade;
drop table if exists catalog_resource cascade;
drop table if exists catalog_playbook cascade;
drop table if exists catalog_resource_versions cascade;
drop table if exists catalog_assistant_releases cascade;
drop table if exists knowledge_base cascade;
drop table if exists knowledge_release cascade;

truncate table
    catalog_ref_resource_binding,
    catalog_ref_knowledge_binding,
    catalog_ref_release_resource,
    catalog_ref_release_knowledge;

create table catalog_domain (
    id varchar(64) primary key,
    name varchar(255) not null,
    description text
);

create table catalog_scenario (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    goal text not null,
    version varchar(32) not null,
    version_status varchar(32) not null,
    version_updated_at timestamp with time zone not null
);

create table catalog_assistant (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    name varchar(255) not null,
    description text,
    version varchar(32) not null,
    version_status varchar(32) not null,
    version_updated_at timestamp with time zone not null,
    primary_agent_id varchar(64),
    owner_policy jsonb not null,
    session_policy jsonb not null,
    reply_policy jsonb not null,
    playbook_policy jsonb not null,
    model_policy jsonb not null,
    privacy_model_resource_id varchar(64),
    privacy_mapping_enabled boolean not null default false,
    knowledge_access_policy jsonb not null,
    memory_policy jsonb not null
);

create table catalog_agent (
    id varchar(64) primary key,
    assistant_id varchar(64) not null,
    name varchar(255) not null,
    role varchar(64) not null,
    responsibility text,
    execution_policy jsonb not null,
    can_own_session boolean not null,
    allowed_actions jsonb not null,
    switchable_owner_agent_ids jsonb not null,
    playbook_ids jsonb not null
);

create table catalog_playbook (
    id varchar(64) primary key,
    assistant_id varchar(64) not null,
    name varchar(255) not null,
    description text,
    input_schema text,
    result_schema text,
    execution_policy jsonb not null,
    allow_human_task boolean not null,
    allow_external_interaction boolean not null,
    entry_node_key varchar(255) not null,
    nodes jsonb not null,
    edges jsonb not null
);

create table catalog_resource (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    type varchar(32) not null,
    share_scope varchar(32) not null,
    owner_type varchar(32) not null,
    owner_id varchar(64) not null,
    summary text,
    steward varchar(255),
    tags jsonb not null
);

create table catalog_resource_versions (
    id varchar(64) primary key,
    resource_id varchar(64) not null,
    version varchar(32) not null,
    status varchar(32) not null,
    summary text,
    config_digest varchar(64) not null,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    configuration jsonb not null
);

create table catalog_assistant_releases (
    id varchar(64) primary key,
    assistant_id varchar(64) not null,
    release_version varchar(32) not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    assistant_knowledge_binding jsonb,
    default_model_binding jsonb,
    privacy_model_binding jsonb,
    privacy_mapping_enabled boolean not null default false,
    resources jsonb not null,
    agents jsonb not null,
    playbooks jsonb not null,
    primary_agent_id varchar(64),
    owner_policy jsonb not null,
    session_policy jsonb not null,
    reply_policy jsonb not null,
    playbook_policy jsonb not null,
    model_policy jsonb not null,
    knowledge_access_policy jsonb not null,
    memory_policy jsonb not null
);

create table knowledge_base (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    share_scope varchar(32) not null,
    owner_type varchar(32) not null,
    owner_id varchar(64) not null,
    summary text,
    steward varchar(255),
    tags jsonb not null
);

create table knowledge_release (
    id varchar(64) primary key,
    knowledge_base_id varchar(64) not null,
    version varchar(32) not null,
    status varchar(32) not null,
    summary text,
    snapshot_id varchar(128) not null,
    retrieval_profile jsonb not null,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone
);

create index idx_catalog_scenario_domain on catalog_scenario (domain_id, name);
create index idx_catalog_assistant_scenario on catalog_assistant (scenario_id, name);
create index idx_catalog_agent_assistant on catalog_agent (assistant_id, name);
create index idx_catalog_playbook_assistant on catalog_playbook (assistant_id, name);
create index idx_catalog_resource_domain on catalog_resource (domain_id, name);
create unique index uk_catalog_resource_version_resource_version on catalog_resource_versions (resource_id, version);
create index idx_catalog_resource_versions_resource on catalog_resource_versions (resource_id, created_at asc, id asc);
create unique index uk_catalog_assistant_release_assistant_version on catalog_assistant_releases (assistant_id, release_version);
create index idx_catalog_assistant_releases_assistant on catalog_assistant_releases (assistant_id, created_at asc, id asc);
create index idx_knowledge_base_domain on knowledge_base (domain_id, name);
create unique index uk_knowledge_release_base_version on knowledge_release (knowledge_base_id, version);
create index idx_knowledge_release_base on knowledge_release (knowledge_base_id, created_at asc, id asc);
