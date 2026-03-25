灵枢 Lynxus
=========

让企业智能体有序协同  
Orchestrate Enterprise Agents

## Monorepo Layout

```text
apps/
  api/         Spring Boot control plane API
  worker/      Temporal workflow worker
  web/         Vue + Ant Design Vue console
  agent-runtime/ Python execution runtime
packages/
  contracts/   OpenAPI and shared frontend contract artifacts
  contracts-jvm/ Shared JVM runtime contracts
infra/
  local/       Docker Compose for local development
docs/
  architecture/ current architecture and startup notes
  progress_record/ archived decisions and stage records
```

## Current Stage

当前仓库更适合定义为“单租户默认、可本地联调的多智能体平台原型”，已经超过单点 MVP 验证阶段，核心能力包括：

- 业务域、场景、助手、智能体、资源和绑定的配置态
- 资源头与版本化配置建模，支持“最新版本 / 生效版本”视图
- 助手发布时冻结资源锚点、agent 执行配置和可执行图快照
- 调用方选择助手后的单助手会话运行态，以及任务、流程、节点状态、人工介入
- 资源区拆分为“资源目录”和“资源新建”两页，分别承接版本治理与按类型建模的资源创建
- 资源类型覆盖知识库、Tool、LLM 模型和 Prompt 模板，其中 Tool 通过 `HTTP / MCP` provider 接入外部能力
- 单助手内真实多智能体图编排，支持 `START / AGENT / HUMAN / END` 节点
- 基于 Temporal 的 `start / wait / signal / resume` 长流程运行，支持人工节点暂停与恢复
- Python agent-runtime 基于发布图动态执行，并提供 KB / Tool / LLM 轻量适配
- 本地 mock 认证、角色切换和未来 OIDC 适配边界

当前仍然保留一些原型阶段边界：

- 认证仍以 mock 为主
- 部分运行态数据仍在内存中维护
- seed 数据和 `demo.local` provider 仍承担本地演示闭环
- API 入口仍同步等待首个 workflow 结果，后续会演进为异步观测链路

## Console IA

控制台采用二级菜单信息架构，按四条主线组织：

- 平台设计：业务域、业务场景
- 助手构建：助手配置、智能体、编排设计
- 资源与发布：资源目录、资源新建
- 运行与观测：会话运行、流程观测

## Documentation Notes

- 当前阶段与范围说明：`docs/lynxus_mvp.md`
- 当前对象模型说明：`docs/mvp_brief_models.md`
- MVP 阶段留档：`docs/progress_record/2026-03-mvp_scope_baseline.md`
- MVP 对象模型留档：`docs/progress_record/2026-03-mvp_object_model_baseline.md`

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

默认会加载运行态演示会话，但不会在启动时自动执行 workflow。
如果你希望保留启动即联调的行为，可以显式开启：

```bash
LYNXUS_CATALOG_SEED_ENABLED=true
LYNXUS_RUNTIME_SEED_EXECUTE_OPENING_MESSAGES=true
```

其中 `LYNXUS_CATALOG_SEED_ENABLED=true` 表示 API 启动时会在 PostgreSQL 目录表为空时自动写入一套演示助手、智能体和资源；如果数据库里已经有数据，则不会重复初始化。

当前默认 seed 会写入一套“客户协同助手”演示图，覆盖：

- FAQ 自动回答
- 售后策略 Tool 调用
- 投诉进入人工节点后等待恢复
- 人工恢复后由协同智能体收口

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

LLM 不提供本地假响应 fallback。只要助手或智能体命中了兼容模型资源，就必须使用 `LYNXUS_OPENAI_COMPATIBLE_*` 这组真实配置。

如果你的模型响应时间较长，可以同步调大 worker 的 Temporal activity 超时：

```bash
LYNXUS_TEMPORAL_ACTIVITY_START_TO_CLOSE_TIMEOUT=PT2M
```

资源中心里会默认提供一个“自定义 OpenAI Compatible 模型”资源，可直接绑定到助手或智能体。

## Runtime Model

当前运行链路已经是发布快照驱动的真实图编排：

- Spring API 负责控制面、发布快照、会话和运行实例
- Temporal workflow 负责长流程托管与人工 signal 恢复
- Python agent-runtime 按发布快照中的 graph 动态执行节点
- HUMAN 节点会生成 checkpoint 与待办，恢复后继续沿图向后执行

关键契约与实现可从这些入口查看：

- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`
- `apps/api/src/main/java/com/lynxus/platform/catalog/CatalogService.java`
- `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeService.java`
- `apps/worker/src/main/java/com/lynxus/worker/workflow/AssistantRunWorkflowImpl.java`
- `apps/agent-runtime/app/main.py`
