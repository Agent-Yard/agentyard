create table if not exists platform_user (
    id varchar(64) primary key,
    username varchar(64) not null unique,
    display_name varchar(255) not null,
    email varchar(255),
    auth_source varchar(32) not null,
    external_subject varchar(255) unique,
    status varchar(32) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    last_login_at timestamp with time zone
);

create table if not exists platform_user_role_binding (
    user_id varchar(64) not null,
    role varchar(32) not null,
    primary key (user_id, role)
);

create index if not exists idx_platform_user_external_subject on platform_user (external_subject);
create index if not exists idx_platform_user_role_binding_user on platform_user_role_binding (user_id);

insert into platform_user (
    id,
    username,
    display_name,
    email,
    auth_source,
    external_subject,
    status,
    created_at,
    updated_at,
    last_login_at
) values (
    'user-admin',
    'admin',
    '平台管理员',
    null,
    'LOCAL_BOOTSTRAP',
    null,
    'ACTIVE',
    current_timestamp,
    current_timestamp,
    null
)
on conflict (id) do update set
    username = excluded.username,
    display_name = excluded.display_name,
    email = excluded.email,
    auth_source = excluded.auth_source,
    external_subject = excluded.external_subject,
    status = excluded.status,
    updated_at = excluded.updated_at,
    last_login_at = excluded.last_login_at;

insert into platform_user_role_binding (user_id, role)
values ('user-admin', 'PLATFORM_ADMIN')
on conflict do nothing;
