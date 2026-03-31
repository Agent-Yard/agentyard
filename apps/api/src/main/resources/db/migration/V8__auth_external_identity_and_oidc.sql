alter table platform_user
    add column if not exists external_issuer varchar(255);

alter table platform_user
    drop constraint if exists platform_user_external_subject_key;

drop index if exists idx_platform_user_external_subject;

create unique index if not exists uk_platform_user_external_identity
    on platform_user (external_issuer, external_subject)
    where external_issuer is not null and external_subject is not null;

create index if not exists idx_platform_user_external_identity
    on platform_user (external_issuer, external_subject);
