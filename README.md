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

- 业务域、场景、助手、智能体、资源和绑定的配置态
- 资源版本管理与智能体绑定时的版本锚定，资源在控制台中统一表达为“最新版本 / 生效版本”
- 助手发布时冻结资源版本快照，形成可追溯的发布记录
- 调用方选择助手后的单助手会话运行态，以及任务、流程、节点状态、人工介入
- 资源区拆分为“资源目录”和“资源新建”两页，分别承接版本治理与按类型建模的资源创建
- 知识库、Skill、MCP 三类资源都采用“资源头 + 版本化配置”建模
- 知识问答 + 升级处理的最小工作流骨架
- 本地 mock 认证、角色切换和未来 OIDC 适配边界

## Console IA

控制台采用二级菜单信息架构，按四条主线组织：

- 平台设计：业务域、业务场景
- 助手构建：助手配置、智能体、编排设计
- 资源与发布：资源目录、资源新建
- 运行与观测：会话运行、流程观测

## Quick Start

### Local dependencies

```bash
cd infra/local
docker compose up -d
```

### Backend

```bash
cp .env.example .env
pnpm dev:api
pnpm dev:worker
pnpm dev:agent-runtime
```

### Frontend

```bash
pnpm install
pnpm dev:web
```

### One Command For Local Debug

```bash
pnpm dev
```

这条命令会在根目录一次启动：

- `apps:api`
- `apps:worker`
- `apps:agent-runtime`
- `apps:web`

如果你已经先起好了 `infra/local` 里的 Docker 依赖，这条命令就够用了。

根目录 `.env` 会被 `pnpm dev`、`pnpm dev:api`、`pnpm dev:worker`、`pnpm dev:agent-runtime` 和 `pnpm dev:web` 自动加载。推荐先执行：

```bash
cp .env.example .env
```

前端可通过根目录 `.env` 或 `apps/web/.env.local` 配置 API 地址：

```bash
VITE_API_BASE_URL=http://localhost:8080/api
```

如果不配置，默认使用 `http://localhost:8080/api`。

如果你要接自定义的 OpenAI-compatible 模型服务，可以在根目录 `.env` 中配置：

```bash
LYNXUS_OPENAI_COMPATIBLE_BASE_URL=http://localhost:11434/v1
LYNXUS_OPENAI_COMPATIBLE_MODEL_ID=custom-compatible-model
LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR=OPENAI_COMPATIBLE_API_KEY
OPENAI_COMPATIBLE_API_KEY=your-token-if-needed
```

资源中心里会默认提供一个“自定义 OpenAI Compatible 模型”资源，可直接绑定到助手或智能体。

> 说明：当前环境未包含 `node`、`pnpm`、`docker`、`gradle` 等工具链运行验证，本仓库已补齐项目骨架、配置和启动说明，后续在具备对应工具链的机器上可继续安装依赖并联调。
