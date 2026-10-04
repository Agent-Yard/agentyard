create table collaboration_actor (
    id text primary key,
    kind text not null check (kind in ('HUMAN', 'AGENT')),
    subject_id text not null,
    display_name text not null,
    active boolean not null default true,
    created_at timestamptz not null,
    unique (kind, subject_id)
);

create table collaboration_organization (
    id text primary key,
    parent_id text references collaboration_organization(id),
    name text not null,
    description text not null,
    roles jsonb not null,
    revision bigint not null,
    created_by text not null references collaboration_actor(id),
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create table collaboration_membership (
    organization_id text not null references collaboration_organization(id),
    actor_id text not null references collaboration_actor(id),
    roles jsonb not null,
    primary key (organization_id, actor_id)
);

create table collaboration_task (
    id text primary key,
    organization_id text references collaboration_organization(id),
    title text not null,
    goal text not null,
    status text not null check (status in ('OPEN', 'ACTIVE', 'WAITING', 'IN_REVIEW', 'COMPLETED', 'CANCELLED')),
    stage text not null default '',
    revision bigint not null,
    content_revision bigint not null,
    attributes jsonb not null,
    policy jsonb not null,
    created_by text not null references collaboration_actor(id),
    submitted_by text references collaboration_actor(id),
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index collaboration_task_updated on collaboration_task(updated_at desc, id);
create index collaboration_task_organization on collaboration_task(organization_id, status);
create table collaboration_participant (
    task_id text not null references collaboration_task(id),
    actor_id text not null references collaboration_actor(id),
    roles jsonb not null,
    primary key (task_id, actor_id)
);
create index collaboration_participant_actor on collaboration_participant(actor_id, task_id);

create table collaboration_context_entry (
    id text primary key,
    task_id text not null references collaboration_task(id),
    kind text not null check (kind in ('FACT', 'HYPOTHESIS', 'PROPOSAL', 'DECISION', 'QUESTION', 'NOTE', 'SUMMARY', 'ARTIFACT')),
    title text not null,
    body jsonb not null,
    state text not null check (state in ('PROPOSED', 'CONFIRMED', 'RESOLVED', 'REJECTED', 'SUPERSEDED')),
    visible_to_roles jsonb not null,
    source jsonb not null,
    author_id text not null references collaboration_actor(id),
    revision bigint not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index collaboration_context_task on collaboration_context_entry(task_id, updated_at desc, id);
create table collaboration_context_version (
    entry_id text not null references collaboration_context_entry(id),
    revision bigint not null,
    task_id text not null references collaboration_task(id),
    actor_id text not null references collaboration_actor(id),
    snapshot jsonb not null,
    created_at timestamptz not null,
    primary key (entry_id, revision)
);
create table collaboration_task_relation (
    task_id text not null references collaboration_task(id),
    related_task_id text not null references collaboration_task(id),
    kind text not null check (kind in ('CHILD', 'DEPENDS_ON', 'RELATED')),
    primary key (task_id, related_task_id, kind),
    check (task_id <> related_task_id)
);
create index collaboration_task_relation_target on collaboration_task_relation(related_task_id);
create table collaboration_task_session (
    task_id text not null references collaboration_task(id),
    session_id text not null references session_runtime_session(id),
    actor_id text not null references collaboration_actor(id),
    created_at timestamptz not null,
    primary key (task_id, session_id, actor_id)
);
create table collaboration_task_event (
    task_id text not null references collaboration_task(id),
    revision bigint not null,
    actor_id text not null references collaboration_actor(id),
    kind text not null,
    data jsonb not null,
    visible_to_roles jsonb not null,
    created_at timestamptz not null,
    primary key (task_id, revision)
);
create table collaboration_task_command (
    task_id text not null references collaboration_task(id),
    actor_id text not null references collaboration_actor(id),
    request_id text not null,
    digest text not null,
    result jsonb not null,
    primary key (task_id, actor_id, request_id)
);
create table collaboration_task_review (
    task_id text not null references collaboration_task(id),
    content_revision bigint not null,
    actor_id text not null references collaboration_actor(id),
    approved boolean not null,
    comment text not null,
    created_at timestamptz not null,
    primary key (task_id, content_revision, actor_id)
);

create table collaboration_execution (
    id text primary key,
    task_id text not null references collaboration_task(id),
    actor_id text not null references collaboration_actor(id),
    requested_by text not null references collaboration_actor(id),
    kind text not null check (kind in ('AGENT', 'PLAYBOOK')),
    status text not null check (status in ('QUEUED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    instruction text not null,
    input jsonb not null,
    runtime_snapshot jsonb not null,
    result jsonb not null,
    waiting jsonb not null,
    failure_reason text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    dispatched_at timestamptz,
    unique (task_id, id)
);
create index collaboration_execution_pending on collaboration_execution(created_at) where dispatched_at is null;
create index collaboration_execution_task on collaboration_execution(task_id, created_at desc);
create table collaboration_execution_signal (
    id text primary key,
    execution_id text not null references collaboration_execution(id),
    actor_id text not null references collaboration_actor(id),
    waiting_id text not null,
    payload jsonb not null,
    created_at timestamptz not null,
    delivered_at timestamptz,
    unique (execution_id, waiting_id)
);
