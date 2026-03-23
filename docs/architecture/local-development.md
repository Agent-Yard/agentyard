# 本地开发说明

## 依赖服务

`infra/local/docker-compose.yml` 包含：

- PostgreSQL
- Redis
- MinIO
- Temporal
- Temporal UI

## 建议启动顺序

1. 启动基础依赖
2. 复制根目录 `.env.example` 为 `.env`
3. 通过 `pnpm dev` 一次启动整套应用

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

## 后续扩展

- 用真实 OIDC 替换 mock 认证
- 用 PostgreSQL 仓储替换当前演示型内存仓储/seed
- 用真实知识库、MCP 和 Skill adapter 替换 mock provider
- 引入 Gradle wrapper、前端 lockfile 和 CI 校验
