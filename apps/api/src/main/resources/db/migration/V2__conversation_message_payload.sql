alter table conversation_message add column payload_type varchar(64);
alter table conversation_message add column payload_json jsonb;
