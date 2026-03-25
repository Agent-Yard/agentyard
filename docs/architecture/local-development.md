# 本地开发说明

## 依赖服务

`infra/local/docker-compose.yml` 包含：

- PostgreSQL
- Redis
- MinIO
- Temporal
- Temporal UI

这些依赖主要服务于当前“控制面 + Temporal + Python runtime + 前端控制台”的本地联调链路。

## 建议启动顺序

1. 启动基础依赖
2. 复制根目录 `.env.example` 为 `.env`
3. 根据需要配置真实模型服务相关环境变量
4. 通过 `pnpm dev` 一次启动整套应用

也可以拆开启动：

- `pnpm dev:api`
- `pnpm dev:worker`
- `pnpm dev:agent-runtime`
- `pnpm dev:web`

## 默认开发约定

- 后端 API：`http://localhost:8080/api`
- 前端开发服务：`http://localhost:5173`
- Agent Runtime：`http://localhost:8090`
- Mock 登录通过 `/api/auth/session` 和 `/api/auth/switch-role`
- 前端如果后端未启动，会回退到内置 mock 数据
- API 启动时可按环境变量自动写入演示 catalog seed
- Worker 会消费同一 Temporal namespace / task queue 下的 assistant run workflow

## 当前开发边界

- 目录数据已落到 PostgreSQL，但部分运行态数据仍在 API 内存结构中维护
- `agent-runtime` 内仍保留 `demo.local` 的 Tool provider 演示闭环
- 若命中真实模型资源，必须在根目录 `.env` 提供对应 API key
- `sendMessage` / `launchTask` 当前仍同步等待 workflow 暴露首个结果

## 后续扩展方向

- 用真实 OIDC 替换 mock 认证
- 补齐运行态持久化与异步订阅式观测
- 用真实知识库和 Tool provider 替换 mock adapter
- 引入 Gradle wrapper、前端 lockfile 和 CI 校验
