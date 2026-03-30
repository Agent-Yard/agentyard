alter table workflow_instance
    add column if not exists agent_turn_state jsonb;
