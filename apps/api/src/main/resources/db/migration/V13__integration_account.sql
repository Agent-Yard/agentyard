create table integration_account (
    id varchar(64) primary key,
    subject_type varchar(32) not null,
    subject_id varchar(128) not null,
    name varchar(128) not null,
    status varchar(32) not null,
    config jsonb not null default '{}',
    external_secret_ref varchar(512),
    credential_ciphertext text,
    credential_fingerprint varchar(128),
    credential_status varchar(32) not null,
    metadata jsonb not null default '{}',
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create index idx_integration_account_subject_updated on integration_account (subject_type, subject_id, updated_at desc);
