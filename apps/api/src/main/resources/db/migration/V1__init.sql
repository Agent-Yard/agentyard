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

create table task_instance (
    id varchar(64) primary key,
    session_id varchar(64),
    scenario_id varchar(64) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    question text not null,
    customer_id varchar(255) not null,
    status varchar(32) not null,
    workflow_instance_id varchar(64) not null,
    created_at timestamp with time zone not null
);

create table workflow_instance (
    id varchar(64) primary key,
    task_id varchar(64) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    status varchar(32) not null,
    summary text,
    final_reply text,
    current_node_key varchar(255),
    escalation_required boolean not null default false,
    checkpoint jsonb,
    resume_task jsonb,
    pause_reason jsonb,
    latest_tool_outcome jsonb,
    resource_anchors jsonb not null,
    nodes jsonb not null,
    tool_calls jsonb not null,
    loaded_skill_resource_version_ids jsonb not null,
    shared_state jsonb not null,
    agent_turn_state jsonb,
    latest_failure jsonb,
    model_hits jsonb
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

create table catalog_orchestration (
    assistant_id varchar(64) primary key,
    payload jsonb not null
);

create table conversation_session (
    id varchar(64) primary key,
    scenario_id varchar(64) not null,
    title varchar(255) not null,
    customer_id varchar(255) not null,
    assistant_id varchar(64) not null,
    assistant_name varchar(255) not null,
    assistant_release_version varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    latest_task_id varchar(64),
    latest_workflow_instance_id varchar(64),
    latest_tool_outcome jsonb,
    latest_resume_task jsonb,
    latest_pause_reason jsonb,
    loaded_skill_resource_version_ids jsonb not null,
    shared_state jsonb not null
);

create table conversation_message (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    role varchar(32) not null,
    sender_type varchar(32) not null,
    sender_id varchar(64),
    sender_name varchar(255) not null,
    content text not null,
    created_at timestamp with time zone not null,
    task_id varchar(64),
    workflow_instance_id varchar(64)
);

create table resume_intervention (
    id varchar(64) primary key,
    workflow_instance_id varchar(64) not null,
    action_type varchar(64) not null,
    action_source varchar(64) not null,
    user_id varchar(255) not null,
    comment text,
    attributes jsonb not null,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    applied_at timestamp with time zone,
    failure_reason text
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

create index idx_task_instance_workflow on task_instance (workflow_instance_id);
create index idx_task_instance_status on task_instance (status);
create index idx_workflow_instance_status on workflow_instance (status, updated_at desc);
create index idx_workflow_instance_failure_code on workflow_instance ((latest_failure ->> 'code'));
create index idx_workflow_instance_failure_category on workflow_instance ((latest_failure ->> 'category'));
create index idx_conversation_session_updated on conversation_session (updated_at desc);
create index idx_conversation_session_latest_workflow on conversation_session (latest_workflow_instance_id);
create index idx_conversation_message_session on conversation_message (session_id, created_at asc, id asc);
create index idx_resume_intervention_workflow on resume_intervention (workflow_instance_id, created_at asc, id asc);
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
