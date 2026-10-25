-- Direct replacement of configuration and execution concepts. Existing prototype data is disposable.
drop table catalog_ref_release_resource, catalog_ref_release_knowledge,
    catalog_ref_resource_binding, catalog_ref_knowledge_binding;
drop table catalog_assistant_releases, catalog_agent, catalog_assistant, catalog_scenario, catalog_domain cascade;
drop table catalog_playbook;
alter table catalog_resource drop column domain_id;
alter table knowledge_base drop column domain_id;

create table catalog_role_template (
    id text primary key,
    name text not null,
    description text not null,
    owner_type text not null,
    owner_id text not null
);
create table catalog_role_template_version (
    id text primary key,
    template_id text not null references catalog_role_template(id),
    version text not null,
    status text not null,
    created_at timestamptz not null,
    published_at timestamptz,
    configuration jsonb not null,
    unique(template_id, version)
);
create table catalog_entry (
    id text primary key,
    name text not null,
    description text not null,
    owner_type text not null,
    owner_id text not null,
    share_scope text not null,
    reception_template_version_id text references catalog_role_template_version(id),
    enabled boolean not null default true,
    allowed_template_ids jsonb not null default '[]',
    capability_grants jsonb not null default '[]'
);
create table catalog_playbook (
    id text primary key,
    name text not null,
    description text not null,
    owner_type text not null,
    owner_id text not null
);
create table catalog_playbook_version (
    id text primary key,
    playbook_id text not null references catalog_playbook(id),
    version text not null,
    status text not null,
    created_at timestamptz not null,
    published_at timestamptz,
    definition jsonb not null,
    unique(playbook_id, version)
);

drop table collaboration_execution_signal, collaboration_execution, collaboration_task_review, collaboration_task_session;
truncate collaboration_task cascade;
alter table collaboration_task add column initiated_by text not null references collaboration_actor(id);
alter table collaboration_task add column confirmer_id text not null references collaboration_actor(id);
alter table collaboration_task drop constraint collaboration_task_status_check;
alter table collaboration_task add constraint collaboration_task_status_check
    check(status in ('OPEN','ACTIVE','WAITING','PAUSED','SUBMITTED','COMPLETED','CANCELLED'));

create table agent_session (
    id text primary key,
    participant_id text not null unique references collaboration_actor(id),
    template_version_id text not null references catalog_role_template_version(id),
    name text not null,
    status text not null check(status in ('ACTIVE','PAUSED','CLOSED')),
    created_by text not null references collaboration_actor(id),
    reply_to_participant_id text references collaboration_actor(id),
    work_memory jsonb not null default '{}',
    revision bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create table agent_reception_binding (
    participant_id text not null references collaboration_actor(id),
    entry_id text not null references catalog_entry(id),
    session_id text not null unique references agent_session(id),
    created_at timestamptz not null,
    primary key(participant_id, entry_id)
);
create table collaboration_task_session (
    task_id text not null references collaboration_task(id),
    session_id text not null references agent_session(id),
    actor_id text not null references collaboration_actor(id),
    created_at timestamptz not null,
    primary key(task_id, session_id)
);
create table collaboration_message (
    id text primary key,
    sender_id text not null references collaboration_actor(id),
    recipient_id text not null references collaboration_actor(id),
    task_id text references collaboration_task(id),
    reply_to_message_id text references collaboration_message(id),
    kind text not null,
    body jsonb not null,
    created_at timestamptz not null
);
create index collaboration_message_recipient on collaboration_message(recipient_id,created_at,id);
create index collaboration_message_sender on collaboration_message(sender_id,created_at,id);
create index collaboration_message_task on collaboration_message(task_id,created_at,id);
create table agent_activation (
    id text primary key,
    session_id text not null references agent_session(id),
    input_message_id text not null unique references collaboration_message(id),
    status text not null check(status in ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    failure_reason text,
    outcome jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    dispatched_at timestamptz,
    cancellation_delivered_at timestamptz
);
create index agent_activation_queue on agent_activation(session_id,created_at,id) where status in ('QUEUED','RUNNING');
create unique index agent_activation_serial on agent_activation(session_id) where status='RUNNING';
create table collaboration_delivery (
    id text primary key,
    message_id text not null unique references collaboration_message(id),
    session_id text references agent_session(id),
    status text not null,
    activation_id text unique references agent_activation(id),
    failure_reason text,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create table collaboration_grant (
    id text primary key,
    participant_id text not null references collaboration_actor(id),
    operation text not null,
    scope_kind text not null,
    scope_id text not null,
    delegable boolean not null default false,
    granted_by text not null references collaboration_actor(id),
    parent_grant_id text references collaboration_grant(id),
    expires_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz not null
);
create index collaboration_grant_lookup on collaboration_grant(participant_id,operation,scope_kind,scope_id) where revoked_at is null;
create index collaboration_grant_parent on collaboration_grant(parent_grant_id);
create table collaboration_decision (
    id text primary key,
    task_id text not null references collaboration_task(id),
    content_revision bigint not null,
    kind text not null,
    decided_by text not null references collaboration_actor(id),
    executed_by text not null references collaboration_actor(id),
    source_message_id text references collaboration_message(id),
    comment text not null,
    created_at timestamptz not null
);
create index collaboration_decision_task on collaboration_decision(task_id,created_at,id);

-- Receipts are scoped to a real caller and operation, never to arbitrary model-supplied identities.
create table collaboration_operation_receipt (
    participant_id text not null references collaboration_actor(id),
    request_id text not null,
    operation text not null,
    digest text not null,
    result jsonb not null,
    created_at timestamptz not null,
    primary key(participant_id,request_id)
);
create table agent_tool_receipt (
    activation_id text not null references agent_activation(id),
    call_id text not null,
    name text not null,
    digest text not null,
    status text not null check(status in ('STARTED','SUCCEEDED','FAILED','UNCERTAIN')),
    arguments jsonb not null,
    result jsonb,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key(activation_id,call_id)
);
create table agent_tool_run (
    id text primary key,
    session_id text not null references agent_session(id),
    participant_id text not null references collaboration_actor(id),
    activation_id text not null references agent_activation(id),
    task_id text references collaboration_task(id),
    playbook_version_id text not null references catalog_playbook_version(id),
    status text not null,
    input jsonb not null,
    result jsonb not null default '{}',
    waiting jsonb not null default '{}',
    failure_reason text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    dispatched_at timestamptz,
    cancellation_delivered_at timestamptz
);
create index agent_tool_run_pending on agent_tool_run(created_at) where dispatched_at is null;
create table agent_tool_run_signal (
    id text primary key,
    tool_run_id text not null references agent_tool_run(id),
    waiting_id text not null,
    participant_id text not null references collaboration_actor(id),
    source_message_id text references collaboration_message(id),
    source text not null,
    payload jsonb not null,
    created_at timestamptz not null,
    delivered_at timestamptz,
    unique(tool_run_id,waiting_id)
);
create table agent_schedule (
    id text primary key,
    session_id text not null references agent_session(id),
    task_id text references collaboration_task(id),
    due_at timestamptz not null,
    instruction text not null,
    interval_seconds bigint check(interval_seconds >= 60),
    active boolean not null default true,
    created_by text not null references collaboration_actor(id),
    created_at timestamptz not null
);
create index agent_schedule_due on agent_schedule(due_at,id) where active;
create table agent_subscription (
    id text primary key,
    session_id text not null references agent_session(id),
    scope_kind text not null,
    scope_id text not null,
    event_kinds jsonb not null,
    active boolean not null default true,
    created_by text not null references collaboration_actor(id),
    created_at timestamptz not null
);
create index agent_subscription_scope on agent_subscription(scope_kind,scope_id) where active;
create table collaboration_event_outbox (
    id text primary key,
    scope_kind text not null,
    scope_id text not null,
    kind text not null,
    actor_id text references collaboration_actor(id),
    payload jsonb not null,
    created_at timestamptz not null,
    delivered_at timestamptz
);
create index collaboration_event_pending on collaboration_event_outbox(created_at,id) where delivered_at is null;
