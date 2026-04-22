# 服务器开发环境说明

## 目标

这套配置解决的不是“本机联调”，而是“把整套 Lynxus 部署到一台开发服务器上，供远程浏览器访问和多人共享调试”。

当前仓库新增了两类入口：

- 常驻环境：`pnpm dev`
- 源码直跑：`pnpm dev:source` / `pnpm dev:source:*`

如果你的目标是远程稳定联调，优先使用 `pnpm dev`；如果你的目标是登录服务器后保留热加载开发体验，使用 `pnpm dev:source`。

## 环境文件

`pnpm dev` 常驻模式使用根目录 `.env.dev` 作为 Docker Compose 的统一变量入口。

`pnpm dev:source` 源码直跑会在原有 `.env` / `.env.local` 之外，额外加载这些覆盖层：

- 根目录 `.env.dev`
- 根目录 `.env.dev.local`
- 应用目录下 `.env.dev`
- 应用目录下 `.env.dev.local`

其中 Web 的 Vite 环境变量统一从仓库根目录 `.env*` / `.env.dev*` 读取；不再把 `apps/web/.env*` 作为主配置入口。

推荐做法：

1. 复制 [`.env.dev.example`](/Users/eric/projects/lynxus/.env.dev.example) 为根目录 `.env.dev`
2. 填好服务器域名、内部鉴权 token、数据库/对象存储/模型密钥
3. 如果是共享环境，优先把 `LYNXUS_AUTH_DEV_BOOTSTRAP_ENABLED` 设为 `false`，并补齐 OIDC 配置

`api` 容器会额外加载根目录 `.env.dev` / `.env.dev.local`，因此 Spring Security 的 OIDC 变量可以直接放进去，例如：

- `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_ID`
- `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_SECRET`
- `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_SCOPE`
- `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_REDIRECT_URI`
- `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_<ID>_ISSUER_URI`

其中 `<ID>` 是你选择的 registration id，例如 `CORP`。

## 常驻模式

新增命令：

- `pnpm dev`
- `pnpm dev:up`
- `pnpm dev:down`
- `pnpm dev:logs`

这套模式通过 Docker Compose 在后台常驻运行整套服务，适合开发服务器、共享测试机和远程浏览器联调。

新增编排文件：

- [infra/dev/docker-compose.yml](/Users/eric/projects/lynxus/infra/dev/docker-compose.yml)

其中 Web 容器通过 [infra/dev/web.conf](/Users/eric/projects/lynxus/infra/dev/web.conf) 反向代理：

- `/api`
- `/oauth2`
- `/login/oauth2`

这样前端仍可使用 `/api` 作为统一入口，不需要在构建阶段硬编码服务器公网地址。

`pnpm dev` 下的 Web 虽然是 `vite build` 后由 nginx 托管，但它仍然属于“开发部署环境”。
前端环境语义不应依赖 `import.meta.env.DEV`；当前统一预留四个环境值：`local`、`dev`、`test`、`prd`。
其中 `infra/dev` 会显式向 Web 传入 `VITE_DEPLOY_ENV=dev`，不要求你在 `.env.dev` 里额外声明。

## 源码直跑

如果需要在服务器上保留热加载，使用：

- `pnpm dev:source`
- `pnpm dev:source:api`
- `pnpm dev:source:worker`
- `pnpm dev:source:knowledge-service`
- `pnpm dev:source:agent-runtime`
- `pnpm dev:source:web`

默认行为和本地模式的差异：

- Java 服务默认 profile 切到 `dev`
- Python 服务默认日志切到 JSON
- Python 内部服务默认监听 `0.0.0.0`
- Web 开发代理目标改为可配置变量 `LYNXUS_WEB_DEV_PROXY_TARGET`

如果你通过 `https` 域名并经由 LB / Nginx 反代访问 `pnpm dev:source`，还需要在 `.env.dev` 中补这些 Vite 变量：

- `LYNXUS_WEB_ALLOWED_HOSTS`
- `LYNXUS_WEB_HMR_PROTOCOL`
- `LYNXUS_WEB_HMR_HOST`
- `LYNXUS_WEB_HMR_CLIENT_PORT`
- `LYNXUS_WEB_HMR_PORT`：仅当 HMR websocket 需要走单独上游端口时再设置

当前统一由 [vite.config.ts](/Users/eric/projects/lynxus/apps/web/vite.config.ts) 读取；`local` 不配置这些变量时会继续使用 Vite 默认行为。

编排内容包括：

- PostgreSQL
- PostgreSQL bootstrap 初始化器
- MinIO
- Redis
- Temporal
- Temporal UI + Basic Auth gateway
- API
- Worker
- Knowledge Service
- Agent Runtime
- Web

## 默认暴露端口

- 控制台：`http://<server-host>:8080`
- 直连 API：`http://<server-host>:18080/api`
- Temporal UI：`http://<server-host>:8088`
- MinIO Console：默认仅监听 `127.0.0.1:9001`
- Redis：默认仅监听 `127.0.0.1:6379`

## 宿主机绑定清单

`pnpm dev` 默认只有以下服务会通过 `ports` 暴露到宿主机：

- Web：`0.0.0.0:8080 -> container:80`
- API：`0.0.0.0:18080 -> container:8080`
- Temporal UI Gateway：`0.0.0.0:8088 -> container:8088`
- MinIO Console：`127.0.0.1:9001 -> container:9001`
- Redis：`127.0.0.1:6379 -> container:6379`

以下服务默认不绑定宿主机，只在 Docker 网络内互通：

- PostgreSQL
- PostgreSQL bootstrap
- Temporal
- Temporal UI
- Knowledge Service
- Agent Runtime
- Worker

如果你需要调整暴露地址，修改这些变量：

- `LYNXUS_DEV_WEB_BIND_ADDRESS`
- `LYNXUS_DEV_API_BIND_ADDRESS`
- `LYNXUS_DEV_TEMPORAL_UI_BIND_ADDRESS`
- `LYNXUS_DEV_MINIO_CONSOLE_BIND_ADDRESS`
- `LYNXUS_DEV_REDIS_BIND_ADDRESS`

如果需要调整，修改 `.env.dev` 中的：

- `LYNXUS_DEV_WEB_BIND_ADDRESS`
- `LYNXUS_DEV_WEB_PORT`
- `LYNXUS_DEV_API_BIND_ADDRESS`
- `LYNXUS_DEV_API_PORT`
- `LYNXUS_DEV_TEMPORAL_UI_BIND_ADDRESS`
- `LYNXUS_DEV_TEMPORAL_UI_PORT`
- `LYNXUS_DEV_REDIS_BIND_ADDRESS`
- `LYNXUS_DEV_REDIS_PORT`

## 当前约束

- `dev` 仍然是开发环境，不是生产发布方案
- 容器编排默认直接 `build` 当前工作树，不包含镜像仓库发布流程
- 如果关闭开发态 bootstrap 登录，当前必须补齐 OIDC 客户端注册，否则 API 会按现有校验逻辑拒绝启动
- MinIO、PostgreSQL、Redis、Temporal 的备份、高可用和监控仍不在这套配置里
