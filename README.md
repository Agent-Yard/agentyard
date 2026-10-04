# AgentYard

让人与 Agent 围绕持续目标、共享上下文和工作成果协作。

[![CI](https://github.com/Agent-Yard/agentyard/actions/workflows/ci.yml/badge.svg)](https://github.com/Agent-Yard/agentyard/actions/workflows/ci.yml)
![status](https://img.shields.io/badge/status-alpha-orange)
![java](https://img.shields.io/badge/java-25-blue)
![spring](https://img.shields.io/badge/spring--boot-4.0.6-6db33f)
![vue](https://img.shields.io/badge/vue-3.5-42b883)
![python](https://img.shields.io/badge/python-3.11+-3776ab)
![temporal](https://img.shields.io/badge/temporal-1.34-orange)

## 项目方向

用户始终与接待 Agent 交流，由它理解需求、建立 Task、组织专业 Agent、跟进进展并提交成果。人和 Agent 都是参与者，可以按权限共享信息、直接协作和作出决定。

- **Session 是 Agent 的持续上下文实例**：前台接待和后台执行遵循同一机制；长期存在不要求进程常驻。
- **Task 是工作信息与共享上下文**：保存目标、参与、材料、进展和成果，黑板属于 Task 内部。
- **角色模板与权限分离**：模板提供职责和默认上下文，实际读写、通信、委派及调度由工具执行边界授权。
- **交付由参与者判断**：执行者提交成果，业务发起者最终确认；用户明确的自然语言决定可由 Agent 代理落地。
- **组织结构按需要形成**：成员、职责、授权和工作关系支持内部与外部协作，不要求普通用户先配置一棵管理层级。

日常使用不要求用户新建或选择 Session，也不提供“开始新工作”入口让用户自行划分上下文。相关工作如何拆分、委派或继续，由 Agent 结合业务意图组织。

完整定义与取舍见[目标架构](docs/architecture/agent-collaboration.md)。

## 当前状态

项目处于 alpha。**上述目标已记录，代码尚未完成相应重构。** 当前代码已有 Task/组织工作台、内容版本与授权、后台执行、Session 对话、Playbook、知识检索及渠道扩展，但仍有两条 Agent 运行链：

| 当前行为 | 目标变化 |
| --- | --- |
| 后台 Task 推理使用合成 Session ID，未保存真实 Session transcript | 全部 Agent 运行关联真实持久实例 |
| 目录 Agent 依赖 Assistant Release | 独立角色模板与实例配置 |
| Task 工作台手工组织执行 | 持续接待 Agent 组织协作 |
| Session Owner 切换、特殊 handoff 和会话专属流程 | 实例间协作消息、明确控制指令和流程工具 |

当前实际能力与限制见[代码现状](docs/architecture/code-framework.md)，改造工作和真实场景验收见[实施计划](docs/todo/agent-collaboration-refactor.md)。允许直接重构数据、接口和配置，不以兼容旧数据为目标。

## 服务与仓库

| 目录 | 职责 |
| --- | --- |
| apps/api | 控制面、协作命令、配置与运行查询 |
| apps/channel-gateway | 渠道 Provider、入站归一化、出站投递 |
| apps/worker | Temporal 持久执行、等待与调度 |
| apps/agent-runtime | 模型与工具循环、上下文、隐私、Tool Connector |
| apps/knowledge-service | 知识导入、索引快照与检索 |
| apps/web | 日常交流、协作信息及管理配置界面 |
| apps/site | 独立项目介绍站点 |
| packages | 共享契约、持久化、Redis、扩展协议与 SDK |
| deploy / scripts | 各环境部署材料与源码启动脚本 |

技术基础继续采用 Spring Boot、Temporal、Python/FastAPI、Vue、PostgreSQL、Redis 和 S3-compatible 存储。扩展使用 Channel Provider / Tool Connector 协议。详见[技术路线](docs/technical_route.md)和[完整目录](docs/project_structure.md)。

## 演示

以下为已有实现的历史演示，界面与运行方式不代表待落地的新入口：

- [会话演示](https://www.bilibili.com/video/BV1eDLp6hEV4/)
- [配置管理演示](https://www.bilibili.com/video/BV1eSLp6wE9X/)

## 快速开始

### 0. 前置条件

- Java 25
- Node.js 22 + pnpm 9.12.0
- Python 3.11+ 与 [`uv`](https://docs.astral.sh/uv/)
- Docker / Docker Compose

### 1. 启动本地依赖

```bash
docker compose -f deploy/local/docker-compose.yml up -d
```

依赖包含 PostgreSQL（自带 `pgvector` / `pg_trgm`）、MinIO、Redis、Temporal 与 sandbox。
PostgreSQL 启动时会自动准备 `agentyard_core`、`agentyard_channel_gateway`、`agentyard_knowledge`、`agentyard_agent_runtime` 四个库，分别给 API/worker、channel-gateway、knowledge service、agent-runtime 使用。

### 2. 准备环境变量

```bash
cp .env.example .env
```

根目录 `.env` 会被所有 `pnpm local:*` 与 `pnpm local` 自动加载。示例已默认把 API、channel-gateway、knowledge service 指向不同数据库，避免互相污染。

### 3. 安装依赖

```bash
pnpm install
uv sync --all-packages
```

Python 依赖统一由根目录 `uv` workspace 管理；`uv` 会自动创建并维护虚拟环境。

### 4. 启动应用

一次拉起：

```bash
pnpm local
```

按服务拆开启动：

```bash
pnpm local:api
pnpm local:channel-gateway
pnpm local:worker
pnpm local:knowledge-service
pnpm local:agent-runtime
pnpm local:web
```

启动后常用入口：

| 服务 | 地址 |
| --- | --- |
| Web 控制台 | <http://localhost:5173> |
| API | <http://localhost:8080/api> |
| Channel Gateway | <http://localhost:8082> |
| Knowledge Service | <http://localhost:8091> |
| Agent Runtime | <http://localhost:8090> |
| Temporal UI | <http://localhost:8088> |
| MinIO Console | <http://localhost:9001> |

### 5. 模型与知识检索配置

至少配置一个真实 LLM provider 的 API key，运行态没有本地假响应 fallback。
OpenAI 兼容 endpoint 示例：

```bash
AGENTYARD_OPENAI_COMPATIBLE_BASE_URL=http://localhost:11434/v1
AGENTYARD_OPENAI_COMPATIBLE_MODEL_ID=custom-compatible-model
AGENTYARD_OPENAI_COMPATIBLE_API_KEY_ENV_VAR=OPENAI_COMPATIBLE_API_KEY
OPENAI_COMPATIBLE_API_KEY=your-token-if-needed
```

知识服务的 embedding provider 单独配置：

```bash
AGENTYARD_KNOWLEDGE_EMBEDDING_BASE_URL=http://localhost:11434/v1
AGENTYARD_KNOWLEDGE_EMBEDDING_MODEL=nomic-embed-text
AGENTYARD_KNOWLEDGE_EMBEDDING_API_KEY=your-token-if-needed
AGENTYARD_KNOWLEDGE_EMBEDDING_DIMENSIONS=768
```

如果模型响应较慢，可同步调大 worker 的 Temporal activity 超时：

```bash
AGENTYARD_TEMPORAL_ACTIVITY_START_TO_CLOSE_TIMEOUT=PT2M
```

当前代码仍需要在控制台或 API 配置目录、资源及发布后使用。持续接待、自动建 Task 与委派的目标入口待本轮架构方案落地。

### 6. 构建前端与站点

根目录 `pnpm build` 会顺序构建控制台 `@agentyard/web` 与静态项目站点 `@agentyard/site`。本地运行主链路的 `pnpm local` 只启动控制台 `apps/web`，不会启动 `apps/site`；如需调试站点，可单独执行：

```bash
pnpm --filter @agentyard/site dev
```

### 7. 远程 / 共享开发环境

如果你要把整套环境常驻在开发服务器上：

```bash
cp .env.dev.example .env.dev
pnpm dev               # Docker Compose 后台常驻
# 或者
pnpm dev:source        # 在服务器上从源码热加载运行
```

部署布局见[部署说明](deploy/README.md)，开发环境编排见 [deploy/dev/docker-compose.yml](deploy/dev/docker-compose.yml)。

## 测试

```bash
./gradlew :apps:api:test :apps:worker:test :apps:channel-gateway:test
pnpm test:web
uv run --all-packages pytest
# 跨语言协作端到端测试（先 uv sync --all-packages，无需 Docker 或模型密钥）
./gradlew :apps:api:taskE2eTest
```

CI 在 `.github/workflows/ci.yml` 中分别跑 Java / Node / Python 三套检查，并对 jOOQ 生成产物做差异校验。

现有 Task E2E 使用确定性模型服务，并未启用后台 Session transcript 生命周期。它验证当前跨语言执行链，不能替代目标架构验收或真实模型/渠道测试，具体边界见[代码现状](docs/architecture/code-framework.md)。

## 文档

- [文档入口](docs/README.md)
- [目标架构与决策](docs/architecture/agent-collaboration.md)
- [实施与验收计划](docs/todo/agent-collaboration-refactor.md)
- [当前代码框架](docs/architecture/code-framework.md)
- [项目待办](docs/project_todos.md)
- [Extension 接入](docs/architecture/extension-integration.md)
- [Tool Connector 开发](docs/architecture/tool-connector-development.md)
- [外部事件与恢复](docs/architecture/external-interaction-integration.md)
- [部署布局](deploy/README.md)与[测试环境](deploy/test/README-cn.md)

## 参与贡献

先阅读 [AGENTS.md](AGENTS.md)。修改应考虑全部应用、共享契约、数据库、部署和文档的影响；同类逻辑收敛到共享实现。根据影响范围运行 Java、Node、Python 检查；目标能力只有取得实际验证证据后才标记完成。

仓库：[Agent-Yard/agentyard](https://github.com/Agent-Yard/agentyard)。反馈：[GitHub Issues](https://github.com/Agent-Yard/agentyard/issues)。
