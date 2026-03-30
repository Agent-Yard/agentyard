# 本地开发说明

## 依赖服务

`infra/local/docker-compose.yml` 包含：

- PostgreSQL
- PostgreSQL bootstrap 初始化器
- MinIO
- OpenSearch
- Temporal

这些依赖服务于当前“控制面 + Temporal + Python runtime + 前端控制台”的本地联调链路。
其中当前主链路最依赖的是 PostgreSQL、Temporal、MinIO、OpenSearch 和知识服务；知识快照构建与检索默认依赖 OpenSearch。
本地 PostgreSQL 默认会准备独立的 `lynxus_api` 和 `lynxus_knowledge` 数据库，避免 API 的 Flyway 与 knowledge service 的自建表共享同一个 `public` schema。
注意：OpenSearch 2.12+ 即使在 `plugins.security.disabled=true` 时，也会校验 `OPENSEARCH_INITIAL_ADMIN_PASSWORD` 是否为强密码；若密码不符合规则，容器会在启动阶段直接退出。
另外 OpenSearch 首次冷启动通常比其他依赖慢，`knowledge-service` 默认会等待最多 45 秒再执行 seed；如需调整，可设置 `LYNXUS_OPENSEARCH_STARTUP_WAIT_SECONDS`。

可选的观察面板单独放在 `infra/local/docker-compose.dashboards.yml`：

- OpenSearch Dashboards
- Temporal UI

## 本机前置条件

建议本机具备：

- `gradle`
- `pnpm`
- `python3`
- Docker / Docker Compose

本地开发约定在项目根目录创建一个共享 `.venv`，并安装 `apps/agent-runtime`、`apps/knowledge-service` 各自的 `requirements.txt`。

## 建议启动顺序

1. 启动基础依赖
2. 复制根目录 `.env.example` 为 `.env`
3. 执行 `pnpm install`
4. 为项目根目录 `.venv` 安装 Python 依赖
5. 根据需要配置真实模型服务相关环境变量
6. 通过 `pnpm dev` 一次启动整套应用

也可以拆开启动：

- `pnpm dev:api`
- `pnpm dev:worker`
- `pnpm dev:knowledge-service`
- `pnpm dev:agent-runtime`
- `pnpm dev:web`

如果需要 dashboard，再额外执行：

```bash
cd infra/local
docker compose -f docker-compose.yml -f docker-compose.dashboards.yml up -d
```

这些脚本会统一加载：

- 根目录 `.env`
- 根目录 `.env.local`
- 各应用目录下的 `.env`
- 各应用目录下的 `.env.local`

其中 `dev-agent-runtime.sh` 和 `dev-knowledge-service.sh` 会优先使用根目录 `.venv/bin/python`；如需覆盖，可分别设置 `AGENT_RUNTIME_PYTHON_BIN`、`KNOWLEDGE_SERVICE_PYTHON_BIN`。

## 默认开发约定

- 后端 API：`http://localhost:8080/api`
- 前端开发服务：`http://localhost:5173`
- Agent Runtime：`http://localhost:8090`
- Knowledge Service：`http://localhost:8091`
- MinIO Console：`http://localhost:9001`
- OpenSearch：`http://localhost:9200`

启用可选 dashboard 后：

- OpenSearch Dashboards：`http://localhost:5601`
- Temporal UI：`http://localhost:8088`
- Mock 登录通过 `/api/auth/session` 和 `/api/auth/switch-role`
- 前端不再回退到内置 mock 数据；后端未启动时页面请求会直接报错
- API 启动时可按环境变量自动写入演示 catalog seed
- API 启动时会对数据库中的非终态 runtime workflow 主动向 Temporal 做一次对账
- Worker 会消费同一 Temporal namespace / task queue 下的 assistant run workflow
- `dev-agent-runtime.sh` 默认以 `uvicorn --reload` 启动 Python runtime
- `dev-knowledge-service.sh` 默认以 `uvicorn --reload` 启动知识服务

## 当前开发边界

- 目录数据和运行态投影都已落到 PostgreSQL
- `agent-runtime` 内仍保留 `demo.local` 的 Tool provider 演示闭环
- 资源类型已收敛为知识库、Tool、LLM 模型和 Skill
- 若命中真实模型资源，必须在根目录 `.env` 提供对应 API key
- `sendMessage` / `launchTask` 当前仍同步等待 workflow 暴露首个结果

## 后续扩展方向

- 用真实 OIDC 替换 mock 认证
- 补齐异步订阅式运行观测
- 收敛知识检索的线上索引策略、生命周期治理和监控面
- 明确 MinIO / OpenSearch 的线上职责并补齐监控与备份
- 引入 Gradle wrapper 和 CI 校验
