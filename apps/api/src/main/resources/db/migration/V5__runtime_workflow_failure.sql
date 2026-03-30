alter table workflow_instance
    add column if not exists latest_failure jsonb;

create index if not exists idx_workflow_instance_failure_code
    on workflow_instance ((latest_failure ->> 'code'));

create index if not exists idx_workflow_instance_failure_category
    on workflow_instance ((latest_failure ->> 'category'));
