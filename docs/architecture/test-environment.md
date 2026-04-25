# 测试环境部署说明

## 目标

测试环境是接近生产的验证环境，不承担本地开发或共享开发机职责。

测试环境发布配置的唯一来源是 [deploy/test](/Users/eric/projects/lynxus/deploy/test)，共享运行材料来自 [deploy/common](/Users/eric/projects/lynxus/deploy/common)。`infra/` 不保留运行编排，避免同一套部署配置维护两份。

## 部署形态

实际发布按模块分机部署，不假设所有服务堆在一台机器上。

后端和运行依赖按独立 compose 文件发布：

- [api.yml](/Users/eric/projects/lynxus/deploy/test/compose/api.yml)
- [worker.yml](/Users/eric/projects/lynxus/deploy/test/compose/worker.yml)
- [channel-gateway.yml](/Users/eric/projects/lynxus/deploy/test/compose/channel-gateway.yml)
- [knowledge-service.yml](/Users/eric/projects/lynxus/deploy/test/compose/knowledge-service.yml)
- [agent-runtime.yml](/Users/eric/projects/lynxus/deploy/test/compose/agent-runtime.yml)
- [sandbox.yml](/Users/eric/projects/lynxus/deploy/test/compose/sandbox.yml)
- [postgres.yml](/Users/eric/projects/lynxus/deploy/test/compose/postgres.yml)
- [temporal.yml](/Users/eric/projects/lynxus/deploy/test/compose/temporal.yml)
- [temporal-ui.yml](/Users/eric/projects/lynxus/deploy/test/compose/temporal-ui.yml)

Web 不使用 Docker Compose。前端构建为静态资源，由已有 Nginx 单独部署，参考 [deploy/test/web](/Users/eric/projects/lynxus/deploy/test/web)。

## 目标机器目录

推荐目标机器布局：

```text
/opt/lynxus/
  common/
  test/
    compose/
    .env
```

每台机器可以只保留本机模块需要的 compose 文件和 env 变量。PostgreSQL 模块会挂载 `../../common/postgres-bootstrap`，因此需要保持 `common + test` 的相对布局。

启动：

```bash
docker compose --env-file test/.env -f test/compose/api.yml up -d
```

升级：

```bash
docker compose --env-file test/.env -f test/compose/api.yml pull
docker compose --env-file test/.env -f test/compose/api.yml up -d
```

## 环境文件

[deploy/test/.env.example](/Users/eric/projects/lynxus/deploy/test/.env.example) 是示例模板。真实 `.env` 由部署系统、运维人员或 secret 管理工具下发到目标机器，不随镜像发布。

每台机器可以只保留本模块需要的变量，但跨服务地址必须使用测试环境内网域名或私网 IP，不要默认使用 `127.0.0.1`。

## 前端静态部署

发布流水线构建：

```bash
VITE_API_BASE_URL=/api VITE_DEPLOY_ENV=test pnpm --filter @lynxus/web build
```

将 `apps/web/dist/` 发布到 Web/Nginx 机器，例如：

```text
/opt/lynxus/web/
```

Nginx 负责：

- `try_files $uri $uri/ /index.html`
- `/api` 反向代理到 API 机器
- `/oauth2` 反向代理到 API 机器
- `/login/oauth2` 反向代理到 API 机器
- TLS、域名、访问日志和静态资源缓存策略

## 依赖边界

测试环境使用已有：

- Redis
- S3-compatible object storage
- Nginx

PostgreSQL 可以使用已有/托管服务，也可以作为独立模块发布。没有现成 PostgreSQL 时，使用 [postgres.yml](/Users/eric/projects/lynxus/deploy/test/compose/postgres.yml) 单独部署。

测试环境没有现成 Temporal，因此 Temporal 作为独立模块发布。Sandbox 也作为独立模块发布，不并入 Worker 或应用组。

## 初始化顺序

各模块 compose 文件是故意独立的，因为实际部署可能分布在不同机器上。跨模块依赖不通过 `depends_on` 表达，必须由发布流水线或运维 runbook 保证。

使用本发布包自建 PostgreSQL 时，推荐顺序：

1. PostgreSQL

   ```bash
   docker compose --env-file test/.env -f test/compose/postgres.yml up -d
   ```

   需要等待 `postgres-bootstrap` 成功退出。它负责创建 `lynxus_core`、`lynxus_knowledge`、`temporal`、`temporal_visibility`，并启用知识库扩展。

2. Temporal

   ```bash
   docker compose --env-file test/.env -f test/compose/temporal.yml up -d
   ```

3. Sandbox

   ```bash
   docker compose --env-file test/.env -f test/compose/sandbox.yml up -d
   ```

4. 内部服务

   ```bash
   docker compose --env-file test/.env -f test/compose/channel-gateway.yml up -d
   docker compose --env-file test/.env -f test/compose/knowledge-service.yml up -d
   docker compose --env-file test/.env -f test/compose/agent-runtime.yml up -d
   ```

5. Worker

   ```bash
   docker compose --env-file test/.env -f test/compose/worker.yml up -d
   ```

6. API

   ```bash
   docker compose --env-file test/.env -f test/compose/api.yml up -d
   ```

7. Web 静态资源与 Nginx

如果使用已有或托管 PostgreSQL，则部署侧必须提前准备好 `lynxus_core`、`lynxus_knowledge`、`temporal`、`temporal_visibility`，并在 `lynxus_knowledge` 中启用 `vector` 和 `pg_trgm`。

## 依赖矩阵

| 模块 | 依赖 |
| --- | --- |
| postgres | 持久化磁盘 |
| temporal | PostgreSQL `temporal`、`temporal_visibility` |
| sandbox | 无 Lynxus 服务依赖 |
| channel-gateway | PostgreSQL `lynxus_core` |
| knowledge-service | PostgreSQL `lynxus_knowledge`、S3-compatible object storage、embedding provider |
| agent-runtime | Redis、API URL、Knowledge Service URL、按需配置模型供应商密钥 |
| worker | PostgreSQL `lynxus_core`、Redis、Temporal、Agent Runtime、Knowledge Service、Sandbox |
| api | PostgreSQL `lynxus_core`、Redis、Temporal、Knowledge Service、Channel Gateway、OIDC |
| web | Nginx 下的 `/api`、`/oauth2`、`/login/oauth2` 反向代理 |

## PostgreSQL 要求

使用 PostgreSQL 16+，推荐 PostgreSQL 17。

Knowledge database 必须具备：

- `pgvector`
- `pg_trgm`
- `tsvector`
- `pgvector` HNSW index 支持

自建测试 PostgreSQL 时，`postgres` 模块会启动 PostgreSQL，并通过 [deploy/common/postgres-bootstrap/init-databases.sh](/Users/eric/projects/lynxus/deploy/common/postgres-bootstrap/init-databases.sh) 这个一次性 bootstrap 创建：

- `lynxus_core`
- `lynxus_knowledge`
- `temporal`
- `temporal_visibility`

同时会在 `lynxus_knowledge` 中启用：

- `vector`
- `pg_trgm`

## Redis 隔离

测试环境复用已有 Redis 时，必须配置环境级隔离：

```dotenv
LYNXUS_REDIS_KEY_PREFIX=lynxus:test
LYNXUS_SESSION_REDIS_NAMESPACE=lynxus:test:session:http
LYNXUS_PRIVACY_SESSION_STORE_KEY_PREFIX=lynxus:test:privacy:session
```

不要仅依赖 Redis database index 做环境隔离。
