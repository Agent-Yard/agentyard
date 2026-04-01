alter table workflow_instance
    add column if not exists model_hits jsonb;
