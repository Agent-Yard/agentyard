create table integration_account (
    id varchar(64) primary key,
    connector_type varchar(64) not null,
    name varchar(255) not null,
    status varchar(32) not null,
    config jsonb not null,
    credential_ciphertext text,
    credential_fingerprint varchar(128),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_integration_account_connector_updated on integration_account (connector_type, updated_at desc);
