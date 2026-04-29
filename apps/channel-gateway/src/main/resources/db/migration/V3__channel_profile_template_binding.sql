create table channel_profile_template_binding (
    id varchar(64) primary key,
    channel_profile_id varchar(64) not null references channel_profile(id),
    assistant_id varchar(64) not null,
    message_type varchar(64) not null,
    message_subtype varchar(128) not null,
    message_version varchar(64) not null,
    external_template_id varchar(255) not null,
    external_template_version varchar(128),
    enabled boolean not null,
    variable_schema jsonb not null,
    display_name varchar(255) not null,
    external_edit_url varchar(1024),
    revision bigint not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create unique index uk_channel_profile_template_binding_natural_key
    on channel_profile_template_binding (
        channel_profile_id,
        assistant_id,
        message_type,
        message_subtype,
        message_version
    );
create index idx_channel_profile_template_binding_profile_updated
    on channel_profile_template_binding (channel_profile_id, updated_at desc);
create index idx_channel_profile_template_binding_enabled_lookup
    on channel_profile_template_binding (
        channel_profile_id,
        assistant_id,
        message_type,
        message_subtype,
        message_version,
        enabled
    );
