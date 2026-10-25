-- A cancellation decision and a late execution receipt are separate durable facts.
alter table agent_activation add column completion_received_at timestamptz;
