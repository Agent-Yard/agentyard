# 仓库结构

更新：2026-10-05。本文导航当前物理目录和模块职责。核心概念见[目标架构](architecture/agent-collaboration.md)，实际行为见[代码现状](architecture/code-framework.md)。

## 1. Monorepo

```text
.github/workflows/       CI
apps/
  api/                  Spring Boot 控制面 API
  channel-gateway/      Channel Provider 运行时与渠道边界
  worker/               Temporal workflow worker
  web/                  Vue + Ant Design Vue 控制台
  site/                 Vite 静态项目站点
  agent-runtime/        Python 模型与工具执行运行时
  knowledge-service/    Python 知识导入、索引与检索
packages/
  contracts/            OpenAPI 与共享 TypeScript 契约源码
  contracts-jvm/        JVM Agent、Task、能力与流程运行契约
  extension-protocol/   扩展协议 OpenAPI、schema、样例、fixtures
  extension-sdk-jvm/    JVM 扩展协议 SDK
  extension-sdk-python/ Python 扩展协议 SDK
  persistence-jvm/      共享 PostgreSQL store 与 jOOQ 生成代码
  python-common/        Python 共享工具
  shared-redis-jvm/     Redis keyspace、锁、通知与序列化
deploy/
  common/               跨环境无密钥材料
  local/                本地开发依赖
  dev/                  常驻开发环境
  test/                 测试环境分模块发布模板
scripts/
  common/               环境装载与进程辅助
  local/                本地源码启动
  dev/                  开发服务器源码启动
docs/
  README.md             文档入口与维护约定
  architecture/         架构、实现、验收基线与接入说明
  todo/                 尚待实施的详细计划
  doing/                仅限当前执行记录，完成自查后清理
  develop_record/       历史记录；本轮不修改
demo/                   演示素材，不作为代码或产品目标依据
gradle/wrapper/         Gradle wrapper
```

`docs/briefing/` 如有项目背景资料，按需阅读，不作为实现事实源。Extension service 模板不在本 monorepo 内；仓库保留协议、SDK 与平台接入边界。

## 2. 构建与启动

- Gradle 多项目：API、Channel Gateway、Worker、contracts-jvm、persistence-jvm、shared-redis-jvm、extension-sdk-jvm。
- pnpm workspace 覆盖 `apps/*`、`packages/*`；当前 Node 包为 Web、Site、extension-protocol。contracts 为共享源码，不是独立 pnpm 包。
- uv workspace：Agent Runtime、Knowledge Service、extension-protocol、extension-sdk-python、python-common。
- 本地依赖：PostgreSQL、MinIO、Redis、Temporal、Sandbox。
- PostgreSQL 初始化四个库：agentyard_core、agentyard_channel_gateway、agentyard_knowledge、agentyard_agent_runtime。

启动和测试命令见[根 README](../README.md)，部署结构见[deploy README](../deploy/README.md)。运行 Java 用 `./gradlew`，Node 用 `pnpm`，Python 用 `uv run`，无需额外 PYTHONPATH。

## 3. 从概念定位代码

| 能力 | 当前代码入口 |
| --- | --- |
| 独立角色、版本和接待入口 | API `catalog`、`CatalogRuntimeMapper`、Web 管理页 |
| 人和 Agent 实例身份 | API `agent` / `task`、`AgentContracts` / `TaskContracts` |
| 持久实例和消息、定时、订阅 | `AgentService`、`AgentDispatcher`、`AgentStore` |
| 模型运行与长上下文 | Worker `agent/ActivationWorkflowImpl`；Runtime `main.py`、`streaming.py`、`transcript_store.py` |
| Task 共享信息与决定 | `CollaborationService`、`TaskCommandService`、`TaskStore` |
| 授权与协作工具 | `AgentAuthorization`、`CapabilityAccess`、`AgentToolService`；Runtime `collaboration_tools.py`、`tooling.py` |
| 流程、等待和外部回调 | `ToolRunService`、Worker `ToolRunWorkflowImpl` / `NodeActivitiesImpl`、`SandboxGateway` |
| 日常交流 | Web `ReceptionPage.vue`、`services/agent.ts`，持久收件箱与SSE |
| 渠道身份与交付 | API `channel`、Channel Gateway、Extension 协议/SDK |
| 知识与外部资料 | Knowledge Service、API `knowledge`、Runtime 连接器和知识工具 |

## 4. 变更需要同步的材料

契约更改同时检查 OpenAPI、TypeScript、JVM record、Python model、扩展 schema/fixtures/SDK，以及所有调用者。数据库变化同步 migrations 与 jOOQ 生成产物；不得只改生成代码。核心行为变化同步 API、Worker、Runtime、Web、渠道和端到端测试。

环境变量与服务职责变化同步根目录环境示例、启动脚本、deploy 模板和 CI。产品形态变化同步根 README、Site 与当前架构文档。

详细工作顺序、模块清单和完成证据见[实施与验收基线](architecture/collaboration-acceptance.md)。历史记录不承担新的规范职责。
