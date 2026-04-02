灵枢 Lynxus
=========

让企业智能体有序协同  
Orchestrate Enterprise Agents

## 当前定位

当前仓库更适合定义为“单租户默认、可本地联调的企业智能体平台原型”。
代码已经不只是概念验证，而是跑通了控制面、发布快照、Temporal 长流程和 Python agent runtime 的一条真实链路。

当前已落地的主能力包括：

- 业务域、业务场景、助手、智能体、知识库和能力资源的配置管理
- 知识库工作台、资源目录、版本发布、引用分析和结构化新建
- 知识库导入任务状态机、失败重试、索引快照构建和检索验证工作台
- 能力资源类型 `TOOL / LLM_MODEL / SKILL`，知识库作为独立一级域治理
- 助手发布时冻结资源版本、知识绑定、智能体执行策略和编排图快照
- 单助手会话、任务、工作流、节点轨迹和人工介入观测
- `START / AGENT / HUMAN / END` 显式图编排
- 基于 Temporal 的 `start / signal / resume` 长流程托管
- Python `agent-runtime` 按发布快照动态执行知识检索、Skill 读取、Tool 调用和模型推理
- 开发态用户会话接口，保留未来对接 OIDC / IAM 的边界

当前仍保留明显的原型边界：

- 默认单租户，复杂租户治理只保留模型边界
- 认证仍以 mock 为主
- 运行态主投影已落 PostgreSQL，并在 API 启动时主动与 Temporal 对账
- `sendMessage` / `launchTask` / `human-action` 已改为启动即返回，前端通过轮询收口运行结果
- workflow 观测页已暴露 `agentTurnState`，可查看最新结构化决策和 turn logs
- MinIO / pgvector 已纳入本地依赖与配置，知识服务当前默认以 PostgreSQL 检索栈作为正式快照检索后端
- 知识库导入与快照构建已改为异步后台任务；控制台会轮询展示进度、失败原因与手动重试入口

## Monorepo Layout

```text
apps/
  api/         Spring Boot control plane API
  worker/      Temporal workflow worker
  web/         Vue + Ant Design Vue console
  agent-runtime/ Python execution runtime
packages/
  contracts/   OpenAPI spec and shared TypeScript contracts
  contracts-jvm/ Shared JVM workflow/runtime contracts
infra/
  local/       Docker Compose for local development
scripts/       Local startup wrappers and env loading
docs/
  architecture/ current architecture and startup notes
  todo/         current backlog and next-step docs
  develop_record/ working notes and refactor records
```

## Console IA

控制台采用二级菜单信息架构，按五条主线组织：

- 平台设计：业务域、业务场景
- 助手构建：助手配置、智能体、编排设计
- 知识库：知识库目录、知识库新建
- 能力资源：资源目录、资源新建
- 运行与观测：会话运行、流程观测

## Documentation Notes

- 当前阶段与范围说明：`docs/lynxus_mvp.md`
- 当前对象模型说明：`docs/mvp_brief_models.md`
- 当前代码结构与本地开发：`docs/architecture/code-framework.md`、`docs/architecture/local-development.md`
- 当前待办：`docs/todo/`
- 记录性文档目录：`docs/develop_record/`

`docs/todo/` 用于维护现行待办；`docs/develop_record/` 主要用于里程碑留档和过程记录，不作为“当前实现”的唯一准绳。

## Quick Start

### 1. 启动本地依赖

```bash
cd infra/local
docker compose up -d
```

默认本地依赖包含 PostgreSQL、MinIO 和 Temporal。
其中 PostgreSQL 会在本地自动准备独立的 `lynxus_api` 和 `lynxus_knowledge` 数据库，分别给 API 和 knowledge service 使用。
知识服务按当前实现默认要求 PostgreSQL 内已启用 `pgvector` 与 `pg_trgm`，不再保留 OpenSearch 或本地嵌入式检索回退。

### 2. 准备环境变量

```bash
cp .env.example .env
```

根目录 `.env` 会被 `pnpm dev`、`pnpm dev:api`、`pnpm dev:worker`、`pnpm dev:knowledge-service`、`pnpm dev:agent-runtime` 和 `pnpm dev:web` 自动加载。
默认示例环境已经把 API 和 knowledge service 指向不同数据库，避免 Flyway 与知识库表互相污染。

### 3. 安装前端与 Python 依赖

```bash
pnpm install
uv sync --all-packages
```

Python 依赖统一由根目录 `uv` workspace 管理。首次使用前请先安装 `uv`，然后在仓库根目录执行 `uv sync --all-packages`，由 `uv` 负责创建和维护虚拟环境。

### 4. 启动应用

单命令启动：

```bash
pnpm dev
```

拆开启动：

```bash
pnpm dev:api
pnpm dev:worker
pnpm dev:knowledge-service
pnpm dev:agent-runtime
pnpm dev:web
```

`pnpm dev` 会在根目录同时拉起：

- `apps:api`
- `apps:worker`
- `apps:knowledge-service`
- `apps:agent-runtime`
- `apps:web`

前提是你本机已经具备 `gradle`、`pnpm`、`python3` 和 `uv`。

如果你只想单独运行或测试 Python 服务，也统一使用 `uv`：

```bash
uv run --all-packages pytest
uv run --directory apps/agent-runtime --package lynxus-agent-runtime pytest tests/test_memory_prompt.py
uv run --directory apps/knowledge-service --package lynxus-knowledge-service pytest tests/test_knowledge_service.py
```

### 5. 常用环境变量

API 启动时会自动对数据库中的非终态 workflow 做一次 Temporal 对账。
当前不再提供内置 demo seed 或 demo SQL 导入路径；目录、资源和知识库数据需由控制台或 API 显式创建。
知识库当前支持文件上传与 URL 导入；运行态只消费已发布知识版本绑定的 `READY` snapshot。

前端默认连接 `http://localhost:8080/api`，可通过根目录 `.env` 或 `apps/web/.env.local` 覆盖：

```bash
VITE_API_BASE_URL=http://localhost:8080/api
```

如果你要接自定义的 OpenAI-compatible 模型服务，可以配置：

```bash
LYNXUS_OPENAI_COMPATIBLE_BASE_URL=http://localhost:11434/v1
LYNXUS_OPENAI_COMPATIBLE_MODEL_ID=custom-compatible-model
LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR=OPENAI_COMPATIBLE_API_KEY
OPENAI_COMPATIBLE_API_KEY=your-token-if-needed
```

知识服务的 embedding provider 也需要单独配置：

```bash
LYNXUS_KNOWLEDGE_EMBEDDING_BASE_URL=http://localhost:11434/v1
LYNXUS_KNOWLEDGE_EMBEDDING_MODEL=nomic-embed-text
LYNXUS_KNOWLEDGE_EMBEDDING_API_KEY=your-token-if-needed
LYNXUS_KNOWLEDGE_EMBEDDING_DIMENSIONS=768
```

当前 LLM 调用没有本地假响应 fallback。只要助手或智能体命中了真实模型资源，就必须提供对应 API key。

本地依赖启动后，常用控制台入口还包括：

- MinIO Console：`http://localhost:9001`

- Temporal UI：`http://localhost:8088`

如果你的模型响应时间较长，可以同步调大 worker 的 Temporal activity 超时：

```bash
LYNXUS_TEMPORAL_ACTIVITY_START_TO_CLOSE_TIMEOUT=PT2M
```

资源需由控制台或 API 显式创建。

## Runtime Model

当前运行链路已经是“发布快照驱动的真实图编排”：

- Spring API 负责控制面、目录数据、发布快照、会话和运行实例聚合
- Temporal worker 负责长流程托管与人工 signal 恢复
- Python `agent-runtime` 按发布快照中的 graph 动态执行节点
- `HUMAN` 节点会生成 checkpoint 与待办，恢复后继续沿图向后执行
- `SKILL` 资源会作为智能体按需读取的技能提示，而不是独立 Prompt 模板

关键契约与实现可从这些入口查看：

- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`
- `apps/api/src/main/java/com/lynxus/platform/catalog/CatalogService.java`
- `apps/api/src/main/java/com/lynxus/platform/runtime/RuntimeService.java`
- `apps/worker/src/main/java/com/lynxus/worker/workflow/AssistantRunWorkflowImpl.java`
- `apps/agent-runtime/lynxus_agent_runtime/main.py`
