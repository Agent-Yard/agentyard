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

项目处于 alpha，现行代码采用统一的 Session / Activation 运行链。持续接待、Agent 委派、Task 共享内容与决定、版本化角色和能力、持久消息/安排、流程工具及渠道接入已完成本版实现与集成验收。

实际模型与工具记录保存在 Runtime PostgreSQL；后台实例使用与接待相同的持久上下文。Task 进展来自真实协作命令，执行结束不自动代表业务完成。

实现分工见[代码框架](docs/architecture/code-framework.md)，工作包及真实场景验证见[实施与验收基线](docs/architecture/collaboration-acceptance.md)。本次为直接架构替换，数据库迁移会清理旧原型的配置、运行及工作数据。

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
PostgreSQL 启动时会自动准备 `agentyard_core`、`agentyard_channel_gateway`、`agentyard_knowledge`、`agentyard_agent_runtime` 四个库，分别给 API、channel-gateway、knowledge service、agent-runtime 使用；Worker 通过控制面 HTTP 访问业务状态，不直连 Core 数据库。

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

### 5. 管理员准备能力，用户直接交流

本地 profile 启用开发登录，可使用 `/api/auth/dev-bootstrap-login`；共享或生产环境按部署说明配置 OIDC。

首次安装由管理员在管理区完成：

1. 创建并发布模型资源，填写实际 provider、modelId、baseUrl 与 apiKeyEnvVar。将密钥保存在 Runtime 环境中对应的变量，例如 `DEEPSEEK_API_KEY`；不把密钥写进提示词。
2. 选择接待、通用执行、审阅等预制角色上下文，选择已发布模型及所需工具/知识/流程版本，发布角色版本。
3. 配置接待入口，指定接待角色、允许委派的模板、实际操作范围以及哪些权限可转授。
4. 普通用户进入首页开始交流。接待关系自动建立；第二项工作仍在同一入口提出，无需创建 Session。

角色模板与授权分开配置。可以只先启用 `task.create`、所需模板的 `agent.create`，再按实际工具添加资源范围；调度、订阅和外部操作需要各自的授权。缺少入口或模型时页面说明原因，普通用户无需承担配置流程。

知识服务的 embedding provider 单独配置：

```bash
AGENTYARD_KNOWLEDGE_EMBEDDING_BASE_URL=http://localhost:11434/v1
AGENTYARD_KNOWLEDGE_EMBEDDING_MODEL=nomic-embed-text
AGENTYARD_KNOWLEDGE_EMBEDDING_API_KEY=your-token-if-needed
AGENTYARD_KNOWLEDGE_EMBEDDING_DIMENSIONS=768
```

`AGENTYARD_AGENT_MAX_TOOL_STEPS` 控制单次模型工具循环，`AGENTYARD_AGENT_MAX_CONCURRENT_ACTIVATIONS` 控制跨 API 实例的运行并发，均默认 32。到达执行边界保留实际记录，不把预算耗尽当成业务完成。

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
# 跨语言协作端到端测试（先 uv sync --all-packages，提供可访问的 Redis）
./gradlew :apps:api:taskE2eTest
```

CI 在 `.github/workflows/ci.yml` 中分别跑 Java / Node / Python 三套检查，并对 jOOQ 生成产物做差异校验。

Task E2E 启动独立 PostgreSQL、Temporal 测试服务、真实 Worker/API/Python Runtime 和确定性模型/工具 HTTP 服务，Runtime 使用真实 transcript 生命周期与 Redis。`-Pdeepseek=true` 另外启用真实模型验收，通过独立的测试变量配置：

| 变量 | 默认值 |
| --- | --- |
| `AGENTYARD_E2E_MODEL_ID` | `deepseek-chat` |
| `AGENTYARD_E2E_MODEL_BASE_URL` | `https://api.deepseek.com/v1` |
| `AGENTYARD_E2E_MODEL_API_KEY_ENV_VAR` | `DEEPSEEK_API_KEY` |

`API_KEY_ENV_VAR` 的值是保存凭证的环境变量名称。先私下装载已有凭证，再指定测试模型；例如凭证已经位于 `OPENAI_COMPATIBLE_API_KEY`：

```bash
source scripts/common/env.sh
load_agentyard_env apps/agent-runtime local
export AGENTYARD_E2E_MODEL_ID=deepseek-flash
export AGENTYARD_E2E_MODEL_BASE_URL=https://api.deepseek.com
export AGENTYARD_E2E_MODEL_API_KEY_ENV_VAR=OPENAI_COMPATIBLE_API_KEY
./gradlew :apps:api:taskE2eTest -Pdeepseek=true
```

报告在 `apps/api/build/reports/tests/taskE2eTest/`，调用证据在 `apps/api/build/task-e2e/`。真实模型、真实外部账号和确定性测试的验证范围分别记录。

## 文档

- [文档入口](docs/README.md)
- [目标架构与决策](docs/architecture/agent-collaboration.md)
- [实施与验收基线](docs/architecture/collaboration-acceptance.md)
- [当前代码框架](docs/architecture/code-framework.md)
- [实际验收结果与范围](docs/architecture/collaboration-verification.md)
- [项目待办](docs/project_todos.md)
- [Extension 接入](docs/architecture/extension-integration.md)
- [Tool Connector 开发](docs/architecture/tool-connector-development.md)
- [外部事件与恢复](docs/architecture/external-interaction-integration.md)
- [部署布局](deploy/README.md)与[测试环境](deploy/test/README-cn.md)

## 参与贡献

先阅读 [AGENTS.md](AGENTS.md)。修改应考虑全部应用、共享契约、数据库、部署和文档的影响；同类逻辑收敛到共享实现。根据影响范围运行 Java、Node、Python 检查；目标能力只有取得实际验证证据后才标记完成。

仓库：[Agent-Yard/agentyard](https://github.com/Agent-Yard/agentyard)。反馈：[GitHub Issues](https://github.com/Agent-Yard/agentyard/issues)。
