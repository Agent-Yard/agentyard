# 本地开发说明

## 依赖服务

`infra/local/docker-compose.yml` 包含：

- PostgreSQL
- Redis
- MinIO
- Temporal
- Temporal UI

这些依赖服务于当前“控制面 + Temporal + Python runtime + 前端控制台”的本地联调链路。
其中当前主链路最依赖的是 PostgreSQL 和 Temporal；Redis / MinIO 已纳入配置，但业务使用仍较轻。

## 本机前置条件

建议本机具备：

- `gradle`
- `pnpm`
- `python3`
- Docker / Docker Compose

`apps/agent-runtime` 建议单独创建 `.venv` 并安装 `requirements.txt`。

## 建议启动顺序

1. 启动基础依赖
2. 复制根目录 `.env.example` 为 `.env`
3. 执行 `pnpm install`
4. 为 `apps/agent-runtime` 准备 Python 虚拟环境和依赖
5. 根据需要配置真实模型服务相关环境变量
6. 通过 `pnpm dev` 一次启动整套应用

也可以拆开启动：

- `pnpm dev:api`
- `pnpm dev:worker`
- `pnpm dev:agent-runtime`
- `pnpm dev:web`

这些脚本会统一加载：

- 根目录 `.env`
- 根目录 `.env.local`
- 各应用目录下的 `.env`
- 各应用目录下的 `.env.local`

## 默认开发约定

- 后端 API：`http://localhost:8080/api`
- 前端开发服务：`http://localhost:5173`
- Agent Runtime：`http://localhost:8090`
- Temporal UI：`http://localhost:8088`
- Mock 登录通过 `/api/auth/session` 和 `/api/auth/switch-role`
- 前端如果后端未启动，会回退到内置 mock 数据
- API 启动时可按环境变量自动写入演示 catalog seed
- API 运行态可自动写入两条演示 session
- Worker 会消费同一 Temporal namespace / task queue 下的 assistant run workflow
- `dev-agent-runtime.sh` 默认以 `uvicorn --reload` 启动 Python runtime

## 当前开发边界

- 目录数据已落到 PostgreSQL，但部分运行态数据仍在 API 内存结构中维护
- `agent-runtime` 内仍保留 `demo.local` 的 Tool provider 演示闭环
- 资源类型已收敛为知识库、Tool、LLM 模型和 Skill
- 若命中真实模型资源，必须在根目录 `.env` 提供对应 API key
- `sendMessage` / `launchTask` 当前仍同步等待 workflow 暴露首个结果

## 后续扩展方向

- 用真实 OIDC 替换 mock 认证
- 补齐运行态持久化与异步订阅式观测
- 用真实知识库和 Tool provider 替换 mock adapter
- 明确 Redis / MinIO 的业务职责并补齐实际接入
- 引入 Gradle wrapper 和 CI 校验
