# 当前代码框架与目标差距

核对日期：2026-10-04。本文记录当前工作区源码的行为，包含尚未提交的 Task 协作实现；不是新架构已经落地的声明。本轮只整理文档，未重新运行应用测试。后续设计以[目标架构](agent-collaboration.md)为准，实施见[改造计划](../todo/agent-collaboration-refactor.md)。

## 1. 当前有两条 Agent 执行链

```text
Session 交流：
  用户 / 渠道 → API SessionRuntimeService → SessionWorkflow
  → Python streaming + transcript → 回复 / Owner 决策 / 会话内 Playbook

Task 执行：
  Task 命令 → collaboration_execution + 待派发记录
  → TaskExecutionDispatcher → TaskExecutionWorkflow
  → Python task_execution → 模型 / 工具 / Task 贡献 → 执行结果
```

两条链都能使用模型、工具、知识和隐私处理，但上下文持久化语义不同。后台 Task Agent **没有真实的 Session 上下文实例**：`task_execution.py` 构造 `task-execution:<executionId>`，并以 `transcript_store=None` 调用推理链路。Task 的输入快照、贡献、执行状态和结果有持久记录，不能据此推断后台模型与工具历史已完整保存。

普通 Session 推理通过 `streaming.py` 和 `transcript_store.py` 保存、重放和提交 transcript；当前身份包含 Session、Owner Agent 和 ownership epoch。存储还有保留时间和清理机制。目标要求统一为真实 Session 的持久上下文，并重审长期实例的清理策略。

## 2. 当前模块边界

| 模块 | 已有职责 | 目标改造重点 |
| --- | --- | --- |
| API | 目录与发布、身份门禁、Task 命令和授权、执行派发、Session 聚合查询、渠道入口 | 实例身份、独立模板、统一协作工具服务、消息投递和代理决定 |
| Worker | TaskExecution、Session、Playbook、知识处理的 Temporal 编排 | Agent 统一激活链；流程成为 ToolRun；持久等待和唤醒 |
| Agent Runtime | 单轮模型/工具循环、transcript、隐私、Task 贡献、连接器 | 所有推理关联真实实例；上下文整理和统一工具执行上下文 |
| Channel Gateway | Provider 注册、入站归一化、出站 frame stream 与回执 | 参与者身份、持续接待路由和消息关联 |
| Knowledge Service | 文件/URL 导入、解析、切片、索引快照、检索 | 保留实际能力，调整归属、授权和资源引用 |
| Web | Task/组织工作台、配置管理、Session 消息及事件展示 | 持续交流入口、成果和待回应事项；独立模板管理 |
| Site | 项目介绍与静态站点 | 随实现更新产品定位和交互说明 |

物理目录、共享包和构建边界见[仓库结构](../project_structure.md)。

## 3. 配置与身份的旧约束

当前目录仍采用 Domain → Scenario → Assistant → Agent。目录 Agent 是配置对象，要求关联 Assistant；发布时 Assistant Release 冻结 Agent、模型、工具、Skill、知识绑定和 Playbook。TaskRuntimeResolver 也要求有效发布以及 Agent/Playbook 在该发布中的配置。

Task Actor 的 `(kind, subjectId)` 中，HUMAN 绑定平台用户，AGENT 绑定目录 Agent。因而“同一模板的两个运行实例分别承担责任”尚未成为主体模型。目标将 Agent Participant 绑定具体 Session，并把目录 Agent 重构为独立 RoleTemplate。

当前 `PlatformAuthorization` 的 PLATFORM_ADMIN、DOMAIN_ADMIN、DEVELOPER、BUSINESS_USER 属于粗粒度控制面门禁。Task 的对象权限另由协作服务处理；这些现有权限不能视为已经覆盖模板实例化、消息、调度、代理用户确认等全部新能力。

## 4. Task 协作已有能力

实现集中在 API `platform/task`、Worker `worker/task`、共享 `TaskContracts` / `TaskStore`、V17 migration 和 Web Task/Organization 页面。

- Organization 记录层级、成员和自定义角色；Task 记录参与、目标、阶段、关系、内容、执行与评审。
- Task 命令携带 requestId、expectedRevision、kind、data，在事务中处理权限、版本冲突、幂等回执和事件。
- 内容有类型、可见范围、作者、来源和修订历史；历史读取继续校验范围。Task detail 只内嵌前 200 项，完整读取有分页接口。
- 派生内容的来源版本与共享范围受校验；关系不会自动复制全部上下文。结构化来源校验不能识别自由文本中被隐瞒的复制来源。
- Execution 保存发布与所选输入快照，未指定时选取可见内容的前 200 项；后续读取还会检查权限。这不是长期实例的动态上下文机制。
- 执行派发、等待回应和取消有持久记录，调度器默认每 2000 ms 尝试派发。人工回应通过 waitingId 关联，旧回应不能恢复另一个等待点。
- Runtime 内置 `task_read_context` 和 `task_contribute`，后者支持内容贡献、内容状态调整和创建子 Task；尚不是完整的角色发现、建实例、委派、直接消息和调度工具集。
- Task 与 Session 已支持多对多显式关联；`TaskSessionBridge` 验证真实 Session 的当前 Owner 和 customer，读取取人和 Agent 的权限交集。后台实例目标需要去掉“必须有 customer”的前提。
- 成果界面主要消费文本与可选 URI，尚无完整的“发布引用后其他实例用相应工具读取成果”的闭环。

当前 Task 状态包括 OPEN、ACTIVE、WAITING、IN_REVIEW、COMPLETED、CANCELLED。评审绑定 contentRevision；实质内容变化会使旧评审失效并令待评审工作回到执行状态。当前还有 requiredApprovals、allowSelfReview、直接子任务及依赖全部完成才可验收等限制。它们是旧实现行为，目标将其替换为参与者的交付与确认决定，不把固定审批规则推广到所有工作。

主要接口：

| 范围 | 当前入口 |
| --- | --- |
| 公开协作 API | `/api/collaboration/actors`、`organizations`、`tasks`、`tasks/{id}/commands`、内容/历史/事件与 Session 关联 |
| 内部执行与 Session 桥接 | `/api/internal/task-executions`、`/api/internal/task-sessions` |
| Python Task 推理 | `POST /task-executions/execute` |

完整字段以 [collaboration OpenAPI](../../packages/contracts/openapi/collaboration.yaml) 为准。这里记录当前接口，不承诺重构兼容。

## 5. Session 与流程的旧行为

当前 Session 按用户与 Assistant 等入口信息创建或复用，维护 currentOwnerAgentId、ownershipEpoch、sharedState、handoff、活跃 Playbook 和空闲期限。SessionWorkflow 根据 Agent 决策回复、切换 Owner、启动 Playbook 或转人工。

Owner 切换会改变 transcript 的 epoch；人工 handoff 期间停止普通用户输入驱动的 Agent 推理。Playbook 是特殊 child workflow，Session 保留单个 activePlaybookRunId，并限制相关 Owner 切换。流程结束可以触发 Owner 再次判断。

这些行为与“Session 就是一个持续 Agent 实例”“普通人工参与属于协作”“Playbook 是可调用的长运行工具”存在直接差距。不能仅在原 Owner 模型之外加一个 AgentInstance 表就认为完成重构。

API 已提供 human-reply、human-resume、external-callback、handoff/end 等会话操作，接入边界见[外部事件与恢复](external-interaction-integration.md)。现有 Session 空闲结束与 workflow 生命周期限制也需调整，不能直接作为持久接待实例的寿命。

## 6. 继续复用的基础能力

### 扩展、知识与隐私

Tool Connector 和 Channel Provider 使用 Extension manifest、注册与 schema。当前 Tool Connector 包含 SIMPLE_HTTP、BUSINESS_CODE_SECRET_HTTP、MCP 及远程扩展；API 与 Web 通过 descriptor 驱动配置。详见[扩展接入](extension-integration.md)和[连接器开发](tool-connector-development.md)。

Knowledge Service 已有 source object → import job → document → index snapshot 链路。文件上传和 URL 抓取最终进入共同导入处理，支持词法、向量、混合检索以及快照读取。模型、工具和知识配置的版本化仍有价值；要移除的是强制 Assistant 整包发布依赖。

Runtime prompt 隐私层区分 SKIP、RULES_ONLY、RULES_THEN_PRIVATE_LLM；规则检测与校验共用检测逻辑。片段缓存保存加密脱敏结果与来源元数据，模型输出恢复及后续历史读取使用相应映射。新实例和消息链需要保留这些保护，不能因统一上下文而跳过处理。

共享 PostgreSQL / jOOQ、Redis keyspace / 锁 / 通知、Session SSE、渠道投递、Integration Account 凭证处理和 usage 采集均可复用。配置版本负责可追溯，授权应在实际操作时检查。

### 脚本与 Sandbox

Session 和 Task 的 Playbook STEP 共用 SandboxGateway。当前网关以 HTTP/1.1 调用 Python / Node，避免 sandbox 代理的 h2c 升级影响请求体；解析 `{success, message, data}` 包装，并保留脚本失败的输出和异常详情。

STEP 结构化输出使用 `__AGENTYARD_RESULT__` 紧接 JSON；标记本身不进行 JSON 序列化。真实 Sandbox 验证命令：

```bash
AGENTYARD_SANDBOX_SMOKE_URL=http://127.0.0.1:8092 ./gradlew :apps:worker:test --tests '*PlaybookSandboxSmokeTest' --rerun-tasks
```

此测试显式启用远端 Sandbox，检查实际 Python / Node STEP 的输入、版本、状态、路由与异常。命令存在不表示本轮已经运行。

## 7. 测试证据的边界

现有 `./gradlew :apps:api:taskE2eTest` 启动临时 PostgreSQL、正式 migrations、API、Temporal 测试服务/工作流和独立 Python FastAPI，使用确定性的 OpenAI-compatible SSE 模型服务驱动真实工具链。场景涉及研究交付、依赖、跨会话读取、取消、拆子任务、并行贡献与版本冲突。

该测试关闭了 Python Session transcript / Redis 启动过程，使用测试身份提供者，并以当前旧模型组织业务。它能验证当前 Task 的跨语言链路，**不能证明后台真实 Session、长期上下文、持续接待、新的最终确认机制已经实现**，也不能替代真实模型、外部渠道、生产故障恢复或浏览器测试。

测试源码位于 `apps/api/src/taskE2e/`；运行后报告写入 `apps/api/build/reports/tests/taskE2eTest/index.html`，Python 日志写入 `apps/api/build/reports/task-e2e/python-runtime.log`。新架构的 E01—E16 验收矩阵见[实施计划](../todo/agent-collaboration-refactor.md)。

## 8. 下一轮代码工作的依据

优先完成实例身份、统一运行和工具授权，再打通“持续接待 → 建 Task → 委派实例 → 实际工具成果 → 用户确认”。当前 Task 工作台和发布管理是可借鉴的组件，不是目标产品入口。新架构落地后应改写本文为新代码现状，并删除已失效的接口与行为说明。
