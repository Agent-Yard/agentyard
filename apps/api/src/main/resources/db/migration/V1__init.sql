create table business_domain (
    id varchar(64) primary key,
    name varchar(255) not null,
    description text
);

create table scenario (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    goal text not null,
    version varchar(32) not null,
    version_status varchar(32) not null
);

create table agent_group (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    name varchar(255) not null,
    description text
);

create table agent (
    id varchar(64) primary key,
    agent_group_id varchar(64) not null,
    name varchar(255) not null,
    role varchar(64) not null,
    responsibility text
);

create table resource (
    id varchar(64) primary key,
    domain_id varchar(64) not null,
    name varchar(255) not null,
    type varchar(32) not null,
    share_scope varchar(32) not null,
    owner_type varchar(32) not null,
    owner_id varchar(64) not null,
    summary text
);

create table resource_binding (
    id varchar(64) primary key,
    resource_id varchar(64) not null,
    consumer_type varchar(32) not null,
    consumer_id varchar(64) not null,
    created_at timestamp not null default current_timestamp
);

create table catalog_domain (
    id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_scenario (
    id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_assistant (
    id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_agent (
    id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_resource (
    id varchar(64) primary key,
    payload jsonb not null
);

create table knowledge_base (
    id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_resource_versions (
    resource_id varchar(64) primary key,
    payload jsonb not null
);

create table knowledge_release (
    knowledge_base_id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_assistant_releases (
    assistant_id varchar(64) primary key,
    payload jsonb not null
);

create table catalog_ref_resource_binding (
    source_type varchar(32) not null,
    source_id varchar(64) not null,
    resource_id varchar(64) not null,
    binding_kind varchar(32) not null,
    primary key (source_type, source_id, resource_id, binding_kind)
);

create table catalog_ref_knowledge_binding (
    source_type varchar(32) not null,
    source_id varchar(64) not null,
    knowledge_base_id varchar(64) not null,
    binding_kind varchar(48) not null,
    primary key (source_type, source_id, knowledge_base_id, binding_kind)
);

create table catalog_ref_release_resource (
    release_id varchar(64) not null,
    assistant_id varchar(64) not null,
    resource_id varchar(64) not null,
    resource_version_id varchar(64),
    resource_version varchar(32),
    primary key (release_id, resource_id)
);

create table catalog_ref_release_knowledge (
    release_id varchar(64) not null,
    assistant_id varchar(64) not null,
    knowledge_base_id varchar(64) not null,
    knowledge_release_id varchar(64),
    primary key (release_id, knowledge_base_id)
);

create table platform_user (
    id varchar(64) primary key,
    username varchar(64) not null unique,
    display_name varchar(255) not null,
    email varchar(255),
    auth_source varchar(32) not null,
    external_subject varchar(255),
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    last_login_at timestamp with time zone,
    external_issuer varchar(255)
);

create table platform_user_role_binding (
    user_id varchar(64) not null,
    role varchar(32) not null,
    primary key (user_id, role)
);

create index idx_ref_resource_binding_resource on catalog_ref_resource_binding (resource_id);
create index idx_ref_knowledge_binding_kb on catalog_ref_knowledge_binding (knowledge_base_id);
create index idx_ref_release_resource_resource on catalog_ref_release_resource (resource_id);
create index idx_ref_release_knowledge_kb on catalog_ref_release_knowledge (knowledge_base_id);
create index idx_platform_user_role_binding_user on platform_user_role_binding (user_id);
create index idx_platform_user_external_identity on platform_user (external_issuer, external_subject);
create unique index uk_platform_user_external_identity
    on platform_user (external_issuer, external_subject)
    where external_issuer is not null and external_subject is not null;

insert into platform_user (
    id,
    username,
    display_name,
    email,
    auth_source,
    external_subject,
    status,
    created_at,
    updated_at,
    last_login_at,
    external_issuer
) values (
    'user-admin',
    'admin',
    '平台管理员',
    null,
    'LOCAL_BOOTSTRAP',
    null,
    'ACTIVE',
    current_timestamp,
    current_timestamp,
    null,
    null
);

insert into platform_user_role_binding (user_id, role)
values ('user-admin', 'PLATFORM_ADMIN');
