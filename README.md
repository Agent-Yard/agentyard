灵枢 Lynxus
=========

让企业智能体有序协同  
Orchestrate Enterprise Agents

## Monorepo Layout

```text
apps/
  api/         Spring Boot control plane API
  worker/      Temporal workflow worker skeleton
  web/         Vue + Ant Design Vue console
packages/
  contracts/   OpenAPI and shared frontend contract artifacts
infra/
  local/       Docker Compose for local development
docs/
  architecture/ framework and startup notes
```

## MVP Focus

当前首版代码框架围绕单租户、单业务域、单业务场景的 MVP 建设，覆盖：

- 业务域、场景、智能体组、智能体、资源和绑定的配置态
- 任务实例、流程实例、节点状态、人工介入的运行态
- 知识问答 + 升级处理的最小工作流骨架
- 本地 mock 认证、角色切换和未来 OIDC 适配边界

## Quick Start

### Local dependencies

```bash
cd infra/local
docker compose up -d
```

### Backend

```bash
gradle :apps:api:bootRun
gradle :apps:worker:bootRun
```

### Frontend

```bash
cp apps/web/.env.example apps/web/.env.local
pnpm install
pnpm dev:web
```

前端可通过 `apps/web/.env.local` 配置 API 地址：

```bash
VITE_API_BASE_URL=http://localhost:8080/api
```

如果不配置，默认使用 `http://localhost:8080/api`。

> 说明：当前环境未包含 `node`、`pnpm`、`docker`、`gradle` 等工具链运行验证，本仓库已补齐项目骨架、配置和启动说明，后续在具备对应工具链的机器上可继续安装依赖并联调。
