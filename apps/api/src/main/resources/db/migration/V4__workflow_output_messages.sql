alter table conversation_message add column message_key varchar(255);

create unique index idx_conversation_message_workflow_message_key
    on conversation_message (workflow_instance_id, message_key);

alter table workflow_instance add column emitted_message_keys jsonb not null default '[]'::jsonb;
