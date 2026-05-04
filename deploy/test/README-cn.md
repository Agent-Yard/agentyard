# 测试部署包

本目录是测试部署配置的唯一来源。当目标模块需要共享的运行时材料时，它会与 `deploy/common` 一起发布。

测试环境按模块进行部署。不要假设所有服务都运行在同一台主机上。

## 布局

```text
deploy/test/
  .env.example
  compose/
    api.yml
    worker.yml
    channel-gateway.yml
    knowledge-service.yml
    agent-runtime.yml
    sandbox.yml
    postgres.yml
    temporal.yml
    temporal-ui.yml
  web/
    nginx.conf.example
    README.md
```

## 主机设置

推荐的目标布局：

```text
/opt/lynxus/
  common/
  test/
    compose/
    .env
```

每台主机可以只保留该主机所需的 compose 文件和环境变量。PostgreSQL 模块需要此相对布局，因为它要挂载 `../../common/postgres-bootstrap`。

启动模块：

```bash
docker compose --env-file test/.env -f test/compose/api.yml up -d
```

在 `.env` 中修改镜像标签后升级模块：

```bash
docker compose --env-file test/.env -f test/compose/api.yml pull
docker compose --env-file test/.env -f test/compose/api.yml up -d
```

## Web 前端

Web 前端不使用 Docker Compose 部署。在发布流水线中构建静态资源，并将其发布到现有的 Nginx 主机上。详见 [web/README.md](/Users/eric/projects/lynxus/deploy/test/web/README.md)。

## 基础设施依赖

测试部署使用现有的 Redis、兼容 S3 的对象存储以及 Nginx。

PostgreSQL 作为一个独立的模块列出，因为测试环境可能使用托管或现有的 PostgreSQL 服务，也可能部署一个专用的 PostgreSQL 主机。当测试环境尚未提供 PostgreSQL 时，使用 [compose/postgres.yml](/Users/eric/projects/lynxus/deploy/test/compose/postgres.yml) 进行部署。

PostgreSQL 模块通过发布的 `common + test` 布局，使用 [../common/postgres-bootstrap/init-databases.sh](/Users/eric/projects/lynxus/deploy/common/postgres-bootstrap/init-databases.sh)。它的一次性引导服务会创建：

- `lynxus_core`
- `lynxus_channel_gateway`
- `lynxus_knowledge`
- `lynxus_agent_runtime`
- `temporal`
- `temporal_visibility`

同时还会在知识库中启用 `vector` 和 `pg_trgm` 扩展。

Temporal 作为一个独立的模块包含在内，因为测试环境没有现成的 Temporal 服务。可以使用 [compose/temporal.yml](/Users/eric/projects/lynxus/deploy/test/compose/temporal.yml) 将其部署在自己的主机或专用的运行主机上。

Sandbox 也是一个独立模块。使用 [compose/sandbox.yml](/Users/eric/projects/lynxus/deploy/test/compose/sandbox.yml) 进行部署。

## 启动顺序

compose 文件有意保持相互独立，因为不同模块可能运行在不同主机上。不通过 `depends_on` 来表达跨模块的顺序；而是由部署流水线或运维手册来保证执行顺序。

当 PostgreSQL 由本包部署时，推荐的顺序：

1. PostgreSQL：

   ```bash
   docker compose --env-file test/.env -f test/compose/postgres.yml up -d
   ```

   在启动 Temporal 或应用服务之前，等待 `postgres-bootstrap` 成功退出。

2. Temporal：

   ```bash
   docker compose --env-file test/.env -f test/compose/temporal.yml up -d
   ```

3. Sandbox：

   ```bash
   docker compose --env-file test/.env -f test/compose/sandbox.yml up -d
   ```

4. 内部服务：

   ```bash
   docker compose --env-file test/.env -f test/compose/channel-gateway.yml up -d
   docker compose --env-file test/.env -f test/compose/knowledge-service.yml up -d
   docker compose --env-file test/.env -f test/compose/agent-runtime.yml up -d
   ```

5. Worker：

   ```bash
   docker compose --env-file test/.env -f test/compose/worker.yml up -d
   ```

6. API：

   ```bash
   docker compose --env-file test/.env -f test/compose/api.yml up -d
   ```

7. Web 静态资源与 Nginx。

当 PostgreSQL 由本包外部管理时，部署人员必须在启动 Temporal 或应用服务之前创建 `lynxus_core`、`lynxus_channel_gateway`、`lynxus_knowledge`、`lynxus_agent_runtime`、`temporal` 和 `temporal_visibility` 数据库。知识库必须启用 `vector` 和 `pg_trgm` 扩展。

## 依赖关系矩阵

| 模块 | 依赖 |
| --- | --- |
| postgres | 持久化磁盘 |
| temporal | PostgreSQL 数据库 `temporal` 和 `temporal_visibility` |
| sandbox | 无需依赖其他 Lynxus 服务 |
| channel-gateway | PostgreSQL 数据库 `lynxus_channel_gateway` |
| knowledge-service | PostgreSQL 数据库 `lynxus_knowledge`、兼容 S3 的对象存储、嵌入（embedding）服务商 |
| agent-runtime | PostgreSQL 数据库 `lynxus_agent_runtime`、Redis、API URL、Knowledge Service URL、按需配置的模型提供商凭证 |
| worker | PostgreSQL 数据库 `lynxus_core`、Redis、Temporal、Agent Runtime、Knowledge Service、Sandbox |
| api | PostgreSQL 数据库 `lynxus_core`、Redis、Temporal、Knowledge Service、Channel Gateway、OIDC |
| web | 通过 Nginx 的 `/api`、`/oauth2` 和 `/login/oauth2` 路由访问 API |

## PostgreSQL 要求

使用 PostgreSQL 16+ 版本，推荐使用 PostgreSQL 17。知识库必须包含支持 HNSW 的 `pgvector` 扩展，并且提供 `pg_trgm` 扩展。
运行态数据源 URL 按服务域配置：`api` 和 `worker` 使用 `LYNXUS_CORE_DATASOURCE_URL`，`channel-gateway` 使用 `LYNXUS_CHANNEL_GATEWAY_DATASOURCE_URL`，`agent-runtime` 使用 `LYNXUS_AGENT_RUNTIME_DATABASE_URL`。不要在部署 env 文件中设置共享的 `SPRING_DATASOURCE_URL`，它会覆盖所有 Spring Boot 服务的数据源。
