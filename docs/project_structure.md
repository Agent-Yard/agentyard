# 仓库结构与改造范围

更新：2026-10-04。本文导航现有物理目录，不把当前配置层级当作目标对象模型。核心概念见[目标架构](architecture/agent-collaboration.md)，实际行为见[代码现状](architecture/code-framework.md)。

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
  contracts-jvm/        JVM 协作、Session、Playbook 和运行契约
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
  architecture/         目标架构、代码现状、接入说明
  todo/                 待实施工作包与验收计划
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

| 目标能力 | 现有主要入口 | 跨模块影响 |
| --- | --- | --- |
| 人与 Agent 实例身份、独立角色模板 | API catalog / task / security、contracts、persistence | 移除目录 Agent 充当运行主体；重构发布与资源归属 |
| 持久 Session 与接待关系 | API session、Worker session、Runtime streaming / transcript_store | 生命周期、渠道路由、隐私、上下文分页和恢复 |
| Agent 激活与后台运行 | API task dispatcher、Worker task / session、Runtime task_execution | 合并现有两条 Agent 路径，更新事件和测试 |
| Task 共享信息与参与者决定 | API task、TaskStore、TaskContracts、Web Task 组件 | 内容版本、来源、成果引用、自然语言代理决定 |
| 工具授权与委派 | API 授权/协作服务、Runtime tooling / tool_connectors | 内置工具、流程子工具、自动上下文读取和凭证使用 |
| 消息、等待、唤醒 | API/Worker session、Channel Gateway、共享 Redis/持久层 | 持久投递、订阅、定时、SSE 和外部回调 |
| 长运行流程工具 | Worker Playbook、SandboxGateway、Runtime tool task | ToolRun 关联、等待恢复、当前授权和结果回原实例 |
| 日常入口与管理配置 | Web router / navigation / pages / services、Site | 持续交流首页，移除旧配置前置与“开始新工作”入口 |
| 知识与资源 | Knowledge Service、API knowledge/catalog、Extension 协议 | 归属与权限更新，保持真实导入、读取和引用能力 |

这是重构导航，不要求按概念新增同名模块或服务。具体类名可随目标调整；共享逻辑应进入适当共享模块，不复制多套路径。

## 4. 变更需要同步的材料

契约更改同时检查 OpenAPI、TypeScript、JVM record、Python model、扩展 schema/fixtures/SDK，以及所有调用者。数据库变化同步 migrations 与 jOOQ 生成产物；不得只改生成代码。核心行为变化同步 API、Worker、Runtime、Web、渠道和端到端测试。

环境变量与服务职责变化同步根目录环境示例、启动脚本、deploy 模板和 CI。产品形态变化同步根 README、Site 与当前架构文档。

详细工作顺序、模块清单和完成证据见[实施与验收计划](todo/agent-collaboration-refactor.md)。历史记录不承担新的规范职责。
