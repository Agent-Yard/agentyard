alter table workflow_instance
    drop column final_reply;

alter table external_interaction_task
    add column source_message_key varchar(255);

update external_interaction_task task
set source_message_key = message.message_key
from conversation_message message
where message.id = task.message_id
  and task.source_message_key is null;

alter table external_interaction_task
    alter column source_message_key set not null;

create unique index uk_external_interaction_task_workflow_message
    on external_interaction_task (workflow_instance_id, source_message_key);
