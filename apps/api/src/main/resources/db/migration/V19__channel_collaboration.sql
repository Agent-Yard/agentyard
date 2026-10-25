truncate channel_session_binding_snapshot;
alter table channel_session_binding_snapshot rename column assistant_id to entry_id;
alter table channel_session_binding_snapshot rename column customer_id to participant_id;
drop index uk_channel_session_binding_snapshot_active_profile_conversation;
create unique index uk_channel_binding_active_conversation_participant
    on channel_session_binding_snapshot(channel_profile_id,external_conversation_id,external_user_id)
    where binding_status='ACTIVE';

-- The control plane records the provider-verified identity; a refreshed route cannot change its audience.
create table channel_participant_identity (
    participant_id text primary key references collaboration_actor(id),
    channel_profile_id text not null,
    external_conversation_id text not null,
    external_user_id text not null,
    unique(channel_profile_id,external_conversation_id,external_user_id)
);

create table channel_collaboration_outbox (
    sequence bigint generated always as identity primary key,
    channel_profile_id text not null,
    binding_id text not null references channel_session_binding_snapshot(binding_id),
    message_id text not null references collaboration_message(id),
    created_at timestamptz not null default now(),
    unique(binding_id,message_id)
);
create index channel_collaboration_outbox_profile_sequence
    on channel_collaboration_outbox(channel_profile_id,sequence);
