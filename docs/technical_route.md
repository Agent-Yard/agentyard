# 技术路线

更新：2026-10-04。方向是以 Agent 为交互核心的人机协作系统；本轮完成方案记录，代码重构待实施。产品和对象边界见[目标架构](architecture/agent-collaboration.md)。

## 1. 保留现有技术基础

以下版本来自仓库构建配置，不表示对外部最新版本的推荐。

| 范围 | 当前技术 |
| --- | --- |
| Web | Vue 3.5、TypeScript 5.9、Vite 8、Ant Design Vue 4 |
| Site | 独立 Vite 静态站点 |
| JVM 服务 | Java 25、Spring Boot 4.0.6 |
| 持久调度 | Temporal SDK 1.34.0 |
| Python 服务 | Python 3.11+、FastAPI 0.115.12、Uvicorn 0.34.0、uv workspace |
| 业务持久化 | PostgreSQL、jOOQ、类型化列与必要 JSONB |
| 知识检索 | PostgreSQL pgvector、pg_trgm、tsvector；LEXICAL / VECTOR / HYBRID |
| 文件 | S3-compatible 对象存储，本地使用 MinIO |
| 通知与协调 | Redis，共享 keyspace、锁、Pub/Sub 和缓存 |
| 协议 | OpenAPI、TypeScript/JVM/Python 契约；Extension manifest/schema/SDK |

本轮架构改造没有要求更换前端框架、引入 LangGraph、引入独立黑板服务或新消息总线。先复用已有能力形成统一运行链。

## 2. 目标运行分工

```text
人 / 外部事件
  → API / Channel Gateway：识别参与者，持久接收与路由
  → Session：持续上下文实例
  → Temporal：调度 Activation、等待工具、恢复与定时
  → Agent Runtime：装配获授权上下文，执行模型和工具循环
  → 工具边界：授权后读写 Task、消息、资源与外部系统
  → 持久结果 / 新消息：回到相应实例或人的交流入口
```

接待与后台工作使用相同的实例和激活机制。RoleTemplate 独立版本化；实例保存实际采用的配置引用，实际权限在操作时判断。Task 负责共享工作信息与决定记录，不能替代 Session 的私有持续上下文。

Playbook 继续使用 Temporal 承载明确步骤和等待，但对 Agent 表现为长运行工具。知识、脚本、Skill、隐私和凭证处理保留各自职责。

## 3. 权威数据与运行资源

| 信息 | 权威职责 |
| --- | --- |
| 参与者、模板、授权、Task、决定、消息/投递 | 控制面与共享 PostgreSQL 持久化服务 |
| Session 的模型/工具历史与检查点 | Runtime transcript 持久化，与控制面实例/Activation 关联 |
| 等待、重试、调度、执行编排 | Temporal；不代替业务记录与用户决定 |
| 文档、导入任务、索引和知识快照 | Knowledge Service 与相应存储 |
| 临时通知、缓存、协调 | Redis；不得是长期上下文或未完成消息的唯一副本 |
| 页面与实时展示 | 从权威记录生成的查询/投影，SSE 用于更新 |

实例存在不要求进程常驻；计算资源按激活分配。长历史通过工作摘要、索引、分页和按需读取管理；摘要保留来源，活跃工作不因缓存过期而遗忘。

工具调用通过同一可信执行上下文传递实例身份、对象范围、授权、决定来源和幂等关联。Web/API 的人工操作与 Agent 工具复用业务服务，避免出现不同权限规则。

## 4. 扩展与资源

继续采用 Channel Provider 与 Tool Connector。扩展服务通过 manifest 声明能力、配置 schema 与 endpoint，注册方式沿用现有环境配置。工具与渠道各自在实际运行边界调用扩展，控制面管理定义、授权及必要凭证操作。

外部文件、知识文档、内部成果、第三方业务对象采用具体工具可解析的引用。按需保存文件或导入知识；不要求所有外部对象先迁入平台，也不新建覆盖所有资源类型的万能表。

Extension 的身份和 ToolRun 关联字段如果需要变化，必须同步协议、fixtures、SDK 与消费者；当前协议不能被视为已经实现目标授权上下文。

## 5. 与当前实现的差异

当前仍有独立 TaskExecution 和 Session Owner 两条链，后台 Task 推理不保存真实 Session transcript，模板依赖 Assistant 发布包，用户入口偏手工 Task 工作台。详细核对见[代码现状](architecture/code-framework.md)。

改造顺序由[W01—W08](todo/agent-collaboration-refactor.md)约束：统一身份和契约，打通接待到确认的完整链路，再扩展等待、直接协作、调度和资源；同时移除旧主链及其消费者。无需兼容已有数据库，不设置长期双轨。

对象权限、持久记录和可恢复等待是主链基本能力。高阶成本治理、生产部署矩阵、大规模性能调优在真实闭环之后安排，见[项目待办](project_todos.md)。
