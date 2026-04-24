# 本地开发说明

## 依赖服务

`infra/local/docker-compose.yml` 包含：

- PostgreSQL
- PostgreSQL bootstrap 初始化器
- MinIO
- Redis
- Temporal
- sandbox

这些依赖服务于当前“控制面 + Temporal + Python runtime + 前端控制台”的本地联调链路。
其中当前主链路最依赖的是 PostgreSQL、Temporal、MinIO、Redis 和知识服务；知识服务通过 S3-compatible object storage 配置访问对象存储，本地默认指向 MinIO。知识快照构建与检索默认依赖 PostgreSQL 内的 `pgvector + pg_trgm + tsvector`。
本地 PostgreSQL 默认会准备 `lynxus_core` 和 `lynxus_knowledge` 两个数据库，避免 API/worker 的核心会话链路与 knowledge service 的自建表共享同一个 `public` schema。

## 本机前置条件

建议本机具备：

- Java 25
- `pnpm`
- `python3`
- `uv`
- Docker / Docker Compose

本地开发约定使用根目录 `uv` workspace 统一管理 Python 依赖。先安装 `uv`，再在仓库根目录执行 `uv sync --all-packages`。

## 建议启动顺序

1. 启动基础依赖
2. 复制根目录 `.env.example` 为 `.env`
3. 执行 `pnpm install`
4. 执行 `uv sync --all-packages` 安装 Python 依赖
5. 根据需要配置真实模型服务相关环境变量
6. 通过 `pnpm local` 一次启动整套应用

也可以拆开启动：

- `pnpm local:api`
- `pnpm local:worker`
- `pnpm local:knowledge-service`
- `pnpm local:agent-runtime`
- `pnpm local:web`

这些脚本会统一加载：

- 根目录 `.env`
- 根目录 `.env.local`
- 各应用目录下的 `.env`
- 各应用目录下的 `.env.local`

其中 Web 的 Vite 环境变量现在统一以仓库根目录 `.env*` 为准；不再建议使用 `apps/web/.env*` 作为主配置入口。

其中 [agent-runtime.sh](/Users/eric/projects/lynxus/scripts/local/agent-runtime.sh) 和 [knowledge-service.sh](/Users/eric/projects/lynxus/scripts/local/knowledge-service.sh) 会直接通过 `uv run` 使用 workspace 环境；运行前需先完成 `uv sync --all-packages`。

## 可配置镜像源

本地 `docker compose` 默认仍使用 upstream 官方镜像，但基础依赖镜像已经支持通过根目录 `.env` 单独覆盖：

- `LYNXUS_IMAGE_POSTGRES`
- `LYNXUS_IMAGE_POSTGRES_BOOTSTRAP`
- `LYNXUS_IMAGE_MINIO`
- `LYNXUS_IMAGE_REDIS`
- `LYNXUS_IMAGE_TEMPORAL`
- `LYNXUS_IMAGE_TEMPORAL_UI`
- `LYNXUS_IMAGE_CADDY`

之所以采用“逐镜像覆盖”而不是统一 registry 前缀，是因为不同上游在 ECR pull-through cache 下的路径规则并不一致；例如 Docker Hub 官方镜像通常需要 `docker-hub/library/...`，第三方镜像和其他 registry 又是另一套路径。

如果你使用私有 AWS ECR pull-through cache，可以直接把这些变量改成完整镜像地址，例如：

```dotenv
LYNXUS_IMAGE_POSTGRES=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/pgvector/pgvector:pg17
LYNXUS_IMAGE_POSTGRES_BOOTSTRAP=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/library/postgres:17.6
LYNXUS_IMAGE_MINIO=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/minio/minio:RELEASE.2025-09-07T16-13-09Z
LYNXUS_IMAGE_REDIS=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/library/redis:7.4-alpine
LYNXUS_IMAGE_TEMPORAL=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/temporalio/auto-setup:1.28.1
LYNXUS_IMAGE_TEMPORAL_UI=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/temporalio/ui:2.39.0
LYNXUS_IMAGE_CADDY=<aws_account_id>.dkr.ecr.<region>.amazonaws.com/docker-hub/library/caddy:2.8.4-alpine
```

实际路径需要以你在 ECR 中配置的 pull-through cache rule 为准。

## 应用容器构建

当前 5 个应用都已提供多阶段 Dockerfile，构建时统一使用仓库根目录作为 build context：

- API：`docker build -f apps/api/Dockerfile -t lynxus-api .`
- Worker：`docker build -f apps/worker/Dockerfile -t lynxus-worker .`
- Web：`docker build -f apps/web/Dockerfile -t lynxus-web .`
- Agent Runtime：`docker build -f apps/agent-runtime/Dockerfile -t lynxus-agent-runtime .`
- Knowledge Service：`docker build -f apps/knowledge-service/Dockerfile -t lynxus-knowledge-service .`

构建策略如下：

- API / Worker：Gradle 在构建阶段产出 Spring Boot 可执行 jar，运行阶段使用 JRE 镜像
- Web：`pnpm build` 产出静态资源，运行阶段使用 Nginx 提供 SPA 文件并处理路由回退
- Agent Runtime / Knowledge Service：`uv build` 产出 wheel，运行阶段使用 Python slim 镜像安装 wheel

Web 默认把 `VITE_API_BASE_URL` 编译为 `/api`。如果前端容器和 API 不在同一反向代理下，需要在构建时显式覆盖，例如：

```bash
docker build \
  -f apps/web/Dockerfile \
  --build-arg VITE_API_BASE_URL=http://127.0.0.1:8080/api \
  -t lynxus-web .
```

## 日志与链路上下文

当前四个后端服务已经统一结构化日志约定：

- 公共字段：`service`、`traceId`、`spanId`、`sessionId`、`workflowId`、`customerId`、`userId`
- 字段语义：`customerId` 表示业务客户或外部终端用户；`userId` 表示平台系统用户
- 跨服务透传头：`traceparent`、`X-Lynxus-Session-Id`、`X-Lynxus-Workflow-Id`、`X-Lynxus-Customer-Id`、`X-Lynxus-User-Id`
- Web 运行态语义：业务用户在会话页发消息时使用 `customerId`；内部登录态下的人工相关操作属于平台用户域，`human-reply / human-resume / handoff-end` 这类操作统一取登录态 `userId`，不再由内部 runtime 请求体传 `operatorId`。`operatorId` 仍保留在 workflow / 对接模型中，供后续非登录态外部接口使用。

本地开发日志格式切换约定：

- API 与 Worker 默认使用 Spring `local` profile，输出可读文本日志；非 `local` profile 输出结构化 JSON
- Agent Runtime 与 Knowledge Service 使用 `LYNXUS_LOG_FORMAT=console|json`
- [agent-runtime.sh](/Users/eric/projects/lynxus/scripts/local/agent-runtime.sh) 与 [knowledge-service.sh](/Users/eric/projects/lynxus/scripts/local/knowledge-service.sh) 默认会设置 `LYNXUS_LOG_FORMAT=console`
- [worker.sh](/Users/eric/projects/lynxus/scripts/local/worker.sh) 默认会设置 `SPRING_PROFILES_ACTIVE=local`

如果需要在本地排查结构化日志链路，可以临时改用：

```bash
LYNXUS_LOG_FORMAT=json pnpm local:agent-runtime
LYNXUS_LOG_FORMAT=json pnpm local:knowledge-service
SPRING_PROFILES_ACTIVE=default pnpm local:api
SPRING_PROFILES_ACTIVE=default pnpm local:worker
```

## 默认开发约定

- 后端 API：`http://127.0.0.1:8080/api`
- 前端开发服务：`http://127.0.0.1:5173`
- Agent Runtime：`http://127.0.0.1:8090`
- Knowledge Service：`http://127.0.0.1:8091`
- Sandbox：`http://127.0.0.1:8092`
- Redis：`127.0.0.1:6379`
- MinIO Console：`http://127.0.0.1:9001`
- Temporal UI：`http://<host>:8088`（对外监听，经过 Basic Auth 保护）
- Python 内部服务鉴权：`LYNXUS_INTERNAL_AUTH_TOKEN`，API / Worker / Agent Runtime / Knowledge Service 必须保持一致
- Redis 统一配置：`LYNXUS_REDIS_HOST / PORT / DATABASE / USERNAME / PASSWORD / SSL_ENABLED`
- 知识服务对象存储：本地默认 `LYNXUS_OBJECT_STORAGE_MODE=object-storage`、`LYNXUS_OBJECT_STORAGE_PROVIDER=minio`、`LYNXUS_OBJECT_STORAGE_ENDPOINT=http://127.0.0.1:9000`、`LYNXUS_OBJECT_STORAGE_BUCKET=lynxus-knowledge`、`LYNXUS_OBJECT_STORAGE_CREATE_BUCKET=true`
- Java 结构化日志：默认非 `local` profile 输出 JSON，本地开发默认文本
- Python 结构化日志：`LYNXUS_LOG_FORMAT` 默认开发态 `console`
- `pnpm local:api` 会默认启用 `local` profile，并打开开发态 bootstrap 登录旁路
- `pnpm local:worker` 会默认启用 `local` profile，便于直接阅读 workflow/activity 日志
- 前端开发服务通过 Vite 代理将 `/api` 转发到 `http://127.0.0.1:8080`
- 控制台未登录时会跳转 `/login`；开发态可通过 `/api/auth/dev-bootstrap-login` 建立本地 bootstrap 会话
- 前端不再回退到内置 mock 数据；后端未启动时页面请求会直接报错
- API 在读取 session 列表、session 详情和投递消息前，会按需向 Temporal 检查对应 session workflow 是否仍开放，并在必要时把已结束会话标记为 `ENDED`
- Worker 会消费同一 Temporal namespace / task queue 下的 `SessionWorkflow` 与 `PlaybookWorkflow`
- [agent-runtime.sh](/Users/eric/projects/lynxus/scripts/local/agent-runtime.sh) 默认监听 `127.0.0.1:8090`，仅供本机 `worker` 调用
- [knowledge-service.sh](/Users/eric/projects/lynxus/scripts/local/knowledge-service.sh) 默认监听 `127.0.0.1:8091`，仅供本机 `api / worker / agent-runtime` 调用
- [web.sh](/Users/eric/projects/lynxus/scripts/local/web.sh) 默认监听 `0.0.0.0:5173`，便于开发时从局域网设备访问
- `pnpm local:api` 默认暴露 `8080` 供前端代理访问；`Temporal UI` 通过 Docker Compose 暴露 `0.0.0.0:8088`
- `Temporal UI` 通过 `temporal-ui-gateway` 代理暴露，默认 Basic Auth 用户名来自 `LYNXUS_TEMPORAL_UI_USERNAME`，密码来自 `LYNXUS_TEMPORAL_UI_PASSWORD`
- `Agent Runtime` 与 `Knowledge Service` 的 HTTP 入口不接浏览器 OIDC 会话，只接受共享 internal token
- API -> Worker -> Python 服务已经统一透传 `traceparent` 与 Lynxus 日志上下文头，跨服务排障时应优先按 `traceId` 聚合日志

## 当前开发边界

- 目录数据和运行态投影都已落到 PostgreSQL
- 资源类型已收敛为知识库、Tool、LLM 模型和 Skill
- Tool 资源版本只维护业务 operation 和 connector 绑定；需要密钥的 Tool Connector 通过 Integration Account 保存凭证，凭证写入要求配置 `LYNXUS_INTEGRATION_CREDENTIAL_ENCRYPTION_KEY`
- 知识库支持文件上传和 URL 导入；导入任务与索引快照都通过知识服务异步推进
- Web 知识库工作台会轮询展示导入 / 快照状态，并支持失败重试与检索验证
- 若命中真实模型资源，必须在根目录 `.env` 提供对应 API key
- `createSession` / `sendMessage` / `human-resume` / `external-callback` / `human-reply` 当前都通过 `/api/session-runtime/...` 入口受理；会话页优先通过 `EventSource` 接收运行态更新，轮询仅作 fallback
- `BUSINESS_USER` 只保留目录只读与运行态使用；目录治理写操作需要 `PLATFORM_ADMIN / DOMAIN_ADMIN / DEVELOPER`

## 后续扩展方向

- 接入真实企业 OIDC 提供方，并按环境关闭开发态 bootstrap 登录旁路
- 补齐异步订阅式运行观测
- 收敛知识检索的线上索引策略、生命周期治理和监控面
- 明确 S3-compatible object storage / pgvector 的线上职责并补齐监控与备份
- 基于 Gradle wrapper 补齐 CI 校验
