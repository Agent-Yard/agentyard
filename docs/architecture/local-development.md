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

可选的观察面板单独放在 `infra/local/docker-compose.dashboards.yml`：

- OpenSearch Dashboards
- Temporal UI

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

其中 `dev-agent-runtime.sh` 和 `dev-knowledge-service.sh` 会直接通过 `uv run` 使用 workspace 环境；运行前需先完成 `uv sync --all-packages`。

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
- `pnpm dev:api` 会默认启用 `local` profile，并打开开发态 bootstrap 登录旁路
- 前端开发服务通过 Vite 代理将 `/api` 转发到 `http://localhost:8080`
- 控制台未登录时会跳转 `/login`；开发态可通过 `/api/auth/dev-bootstrap-login` 建立本地 bootstrap 会话
- 前端不再回退到内置 mock 数据；后端未启动时页面请求会直接报错
- API 启动时会对数据库中的非终态 runtime workflow 主动向 Temporal 做一次对账
- Worker 会消费同一 Temporal namespace / task queue 下的 assistant run workflow
- `dev-agent-runtime.sh` 默认以 `uv run --package lynxus-agent-runtime uvicorn --reload` 启动 Python runtime
- `dev-knowledge-service.sh` 默认以 `uv run --package lynxus-knowledge-service uvicorn --reload` 启动知识服务

## 当前开发边界

- 目录数据和运行态投影都已落到 PostgreSQL
- 资源类型已收敛为知识库、Tool、LLM 模型和 Skill
- 知识库支持文件上传和 URL 导入；导入任务与索引快照都通过知识服务异步推进
- Web 知识库工作台会轮询展示导入 / 快照状态，并支持失败重试与检索验证
- 若命中真实模型资源，必须在根目录 `.env` 提供对应 API key
- `sendMessage` / `launchTask` 当前已改为异步受理后返回，由运行态观测页轮询收口

## 后续扩展方向

- 接入真实企业 OIDC 提供方，并按环境关闭开发态 bootstrap 登录旁路
- 补齐异步订阅式运行观测
- 收敛知识检索的线上索引策略、生命周期治理和监控面
- 明确 MinIO / OpenSearch 的线上职责并补齐监控与备份
- 基于 Gradle wrapper 补齐 CI 校验
