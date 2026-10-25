-- Cancellation remains authoritative while a late real execution receipt is retained once.
alter table agent_tool_run add column completion_received_at timestamptz;
