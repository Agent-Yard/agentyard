-- Active resource bindings (blocks deletion)
create table if not exists catalog_ref_resource_binding (
    source_type   varchar(32)  not null,
    source_id     varchar(64)  not null,
    resource_id   varchar(64)  not null,
    binding_kind  varchar(32)  not null,
    primary key (source_type, source_id, resource_id, binding_kind)
);
create index idx_ref_resource_binding_resource on catalog_ref_resource_binding(resource_id);

-- Active knowledge base bindings (blocks deletion)
create table if not exists catalog_ref_knowledge_binding (
    source_type        varchar(32)  not null,
    source_id          varchar(64)  not null,
    knowledge_base_id  varchar(64)  not null,
    binding_kind       varchar(48)  not null,
    primary key (source_type, source_id, knowledge_base_id, binding_kind)
);
create index idx_ref_knowledge_binding_kb on catalog_ref_knowledge_binding(knowledge_base_id);

-- Release frozen resource anchors (does not block deletion)
create table if not exists catalog_ref_release_resource (
    release_id          varchar(64)  not null,
    assistant_id        varchar(64)  not null,
    resource_id         varchar(64)  not null,
    resource_version_id varchar(64),
    resource_version    varchar(32),
    primary key (release_id, resource_id)
);
create index idx_ref_release_resource_resource on catalog_ref_release_resource(resource_id);

-- Release frozen knowledge anchors (does not block deletion)
create table if not exists catalog_ref_release_knowledge (
    release_id              varchar(64)  not null,
    assistant_id            varchar(64)  not null,
    knowledge_base_id       varchar(64)  not null,
    knowledge_release_id    varchar(64),
    primary key (release_id, knowledge_base_id)
);
create index idx_ref_release_knowledge_kb on catalog_ref_release_knowledge(knowledge_base_id);
