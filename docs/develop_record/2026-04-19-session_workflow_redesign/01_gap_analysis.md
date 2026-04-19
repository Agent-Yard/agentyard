# Session Workflow 重构差异分析

## 1. 分析基线

- 设计基线：`docs/architecture/session_workflow_redesign.md`
- 盘点范围：
  - `apps/api`
  - `apps/worker`
  - `apps/agent-runtime`
  - `packages/contracts`
  - `packages/contracts-jvm`
  - `apps/web`
  - `docs/architecture/code-framework.md`
  - `docs/architecture/external-interaction-integration.md`
  - `docs/todo/runtime_todo.md`
  - `docs/todo/sse_plan.md`

## 2. 根模型差异

### 2.1 现状

- worker 主 workflow 是 `AssistantRunWorkflow`
- Python runtime 仍基于 `LangGraph` 执行 `START / AGENT / HUMAN / END`
- catalog 以 `AssistantOrchestrationDto` 图编排为核心
- API 运行态以 `task_instance / workflow_instance / conversation_session / resume_intervention / external_interaction_task` 为主
- shared contracts 以 `WorkflowResult / StructuredAgentDecision / SessionStatePatch / ResumeAction / ExecutionCheckpoint` 为核心

### 2.2 设计要求

- 顶层核心变为 `session workflow`
- 当前 owner 通过 `AgentDecision.action` 控制会话
- 强业务流程下沉为 `playbook child workflow`
- agent-runtime 只做单轮 owner 决策
- 运行态观测变为 `session event + playbook run`

### 2.3 结论

- 这是根模型替换，不是局部增强
- 旧 `assistant-run graph` 体系中的多数核心对象都不应继续作为主语义保留

## 3. 模块差异清单

> 最终状态说明：
> 本文件记录“初始现状 vs 设计”的差异，以及实施过程中发现的关键偏差。
> 截至当前，主路径差异已全部关闭；剩余内容只作为后续工程化增强建议保留。

### 3.1 `packages/contracts-jvm`

#### 现状

- `WorkflowContracts` 以 graph/orchestration 运行契约为核心：
  - `OrchestrationNodeType`
  - `DecisionType`
  - `ExecutionCheckpoint`
  - `ResumeTaskSnapshot`
  - `PauseReasonSnapshot`
  - `WorkflowResult`
  - `AssistantRunSnapshot.graph`
- `AssistantRunWorkflow` 接口仅支持：
  - `run(WorkflowStartRequest)`
  - `submitResumeAction(ResumeAction)`
  - `currentResult()`

#### 与设计冲突

- 缺少 `session workflow` 和 `playbook workflow` 契约
- 缺少 `AgentDecision` 新动作模型
- 缺少 `session event`、`playbook run`、`session state` 权威结构
- 现有 `WorkflowResult` 假设 runtime 自己推进整个 graph，不符合“worker 控制状态机，runtime 只做 agent turn”

#### 影响判断

- 该模块必须重写为新的共享契约中心

### 3.2 `packages/contracts`

#### 现状

- TS 契约镜像 JVM 侧旧模型：
  - `WorkflowInstance`
  - `StructuredAgentDecision`
  - `AgentTurnState`
  - `ConversationSession.messages`
  - `ExternalInteractionTask`
  - `ResumeIntervention`

#### 与设计冲突

- 前端消费对象还是 workflow/debug 导向，而不是 session event/playbook run 导向
- `SharedSessionState` 仍是 `facts/artifacts/agentScopes` 三段式；设计要求扁平 KV
- 决策动作仍是 `FINAL / TOOL_CALL / SKILL_READ / HUMAN_HANDOFF`

#### 影响判断

- TS 契约必须与 JVM 契约同步整体替换

### 3.3 `apps/worker`

#### 现状

- `AssistantRunWorkflowImpl` 只负责：
  - 调 `startExecution`
  - 若 `WAITING_RESUME` 则 `Workflow.await()` 等待 resume signal
  - 调 `resumeExecution`
- activity 只是把整条执行请求转发给 Python runtime
- worker 不掌握 owner、playbook、session event、reevaluation 等权威语义

#### 与设计冲突

- 设计要求 worker 成为：
  - session 长期状态中心
  - owner 切换执行者
  - playbook child workflow 启动者
  - reevaluation 触发者
  - idle timer / guardrail / BUSY/REJECTED 入口裁决者
- 现状 worker 对这些几乎都没有实现

#### 影响判断

- `apps/worker` 需要从单 workflow 中转层重构为新的多 workflow/多 activity 结构
- 当前已完成：
  - `SessionWorkflow` 已替代旧 `AssistantRunWorkflow` 成为唯一会话 workflow
  - worker 已开始维护 `session snapshot + session event + playbook run` 权威 projection
- 当前状态：
  - worker / API / runtime 的主差异已闭合
  - API 已补齐 `sessionId / 对话绑定键` 串行投递锁，并在 workflow 已结束时把 session 显式落为 `ENDED`

### 3.4 `apps/agent-runtime`

#### 现状

- 直接依赖 `langgraph`
- 输入是 `WorkflowStartRequest / WorkflowResumeRequest`
- 负责 graph traversal、tool loop、skill read、human handoff request、output messages、checkpoint、resume context
- 自己维护 workflow/node 状态，并返回 `WorkflowResult`

#### 与设计冲突

- 设计明确要求完全移除 `LangGraph`
- runtime 不应再处理整个 workflow，不应再返回 node/checkpoint/resumeTask
- runtime 应只完成：
  - prompt 组装
  - 工具调用
  - sharedState 读写
  - 输出 `AgentTurnResult`

#### 影响判断

- `apps/agent-runtime` 需要以“单 agent 单轮推理服务”重写主接口和内部执行循环
- 当前已完成：
  - 旧 `LangGraph` 主链已移除
  - runtime 对外仅保留 `/agent-turns/execute`
- 当前状态：
  - 已具备 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities`
  - 已支持 OpenAI-compatible provider 的真实单轮决策
  - 已具备有上限的推理内 act/tool-result/final-decision 循环
  - `AgentTurnRequest` 已改为携带 assistant release 冻结后的 model / skill / tool descriptor
  - API 启动 session 时已基于 `release.agents + release.resources` 注入运行时快照，不再使用当前 draft agent 配置直接下发
  - tools 已以真实 resource tool definitions 暴露，并按 HTTP / MCP provider config 执行
  - skills 已按“目录先暴露、详情按需加载”的模式进入 runtime 主循环
  - knowledge 检索边界已完成对齐：
    - 设计文档已改为“runtime 远程调用 knowledge-service 完成在线检索”
    - runtime request 已注入冻结 knowledge binding
    - runtime 已补 builtin `knowledge_search / knowledge_read`
  - 仍存在的实现缺口：
    - `PromptRuntimeMessages` 尚未实现“按 memory window / 字节预算裁剪”的运行时上下文组装；当前仍是全量 `sharedState` + 固定最近事件条数
    - `memoryWindowSize` 只下发到 runtime 契约，尚未真正参与 prompt 组装
  - 未配置 provider 时的 deterministic fallback 作为降级路径保留，不构成设计偏差

### 3.5 `apps/api`

#### 现状

- `RuntimeService` 是超大聚合服务，围绕：
  - 创建 session
  - 启动 workflow
  - 维护 workflow/task/session 主投影
  - 处理 external interaction return/callback
  - 处理 human resume
- 数据库表围绕：
  - `task_instance`
  - `workflow_instance`
  - `conversation_session`
  - `conversation_message`
  - `resume_intervention`
  - `external_interaction_task`
  - `external_interaction_event`
- Controller 暴露 `/tasks`、`/workflows/{id}/resume` 等旧入口

#### 与设计冲突

- 设计不再以 task/workflow projection 为中心
- API 需支持：
  - 用户消息 Update 投递
  - session 查询
  - session event 查询
  - playbook run 查询
  - human resume / external callback / handoff end
- `RuntimeService` 当前结构把旧模型强耦合进单个超大类，不符合新架构分层

#### 影响判断

- API runtime 模块必须按新领域边界拆分重构：
  - session command service
  - session query service
  - session repository
  - session event repository
  - playbook run repository
  - temporal gateway
  - projection/query assembler

### 3.6 `apps/api` catalog

#### 现状

- assistant 配置没有 `primaryAgentId`
- agent 没有 owner 白名单与动作白名单
- 独立 playbook 配置对象不存在
- orchestration 是 release snapshot 的核心组成部分

#### 与设计冲突

- 设计要求 assistant/agent/playbook 模型全面改造
- orchestration graph 不应再是主控编排模型

#### 影响判断

- catalog DTO、service、repository、reference analysis、web 对应页面都要重构
- 当前已完成：
  - assistant/agent 已具备 owner/session/playbook policy 基础字段
- 当前状态：
  - 该层主差异已清空；playbook 已纳入 catalog、release snapshot、web 管理页和治理分析

### 3.7 `apps/web`

#### 现状

- 有独立 `OrchestrationPage`
- runtime 页面展示：
  - session message 列表
  - latest workflow / latest task
  - shared state（旧结构）
- workflow 页面展示 resume actions、node execution、agent turn logs
- assistant/agent 编辑页围绕旧模型配置

#### 与设计冲突

- 新模型不再以 orchestration editor 和 workflow debug 为产品中心
- runtime 主视图应关注：
  - session event timeline
  - current owner
  - active playbook / waiting reason
  - human handoff
- assistant/agent 编辑页应支持新配置模型

#### 影响判断

- web 需要同步切换类型、API 调用和页面结构

### 3.8 现有架构文档

#### 当前新增发现

- `sharedState` 最终口径已确认按扁平 KV 处理
- 设计文档 §3.2 中残留的 `facts / artifacts / agentScope` 分区措辞应视为待同步修正文案，而不是实现侧继续保留的另一套模型

### 3.9 `apps/worker` / `apps/api` session-playbook 主链补充差异

#### 当前新增发现

- playbook `STEP / TOOL_TASK` 真实执行链已落地：
  - `STEP` 已通过 sandbox 执行脚本化节点
  - `TOOL_TASK` 已通过 runtime capability 执行固定工具
- `RUN_PLAYBOOK` 的 `playbookInput` schema 校验已落地
- playbook `timeoutPolicy / retryPolicy` 目前只在 contracts / release snapshot 中传递，worker 并未实际执行
- worker 主动持久化已完成，`currentProjection()` 同步链已从 API 主路径移除

#### 现状

- `docs/architecture/code-framework.md` 明确写着：
  - Python runtime 负责图编排和节点推进
  - worker 负责 workflow/activity 编排
- `docs/architecture/external-interaction-integration.md` 基于 `WorkflowResult.outputMessages` 与 interaction task 投影展开
- `docs/todo/runtime_todo.md`、`docs/todo/sse_plan.md` 都是旧 runtime projection 思维

#### 与设计冲突

- 当前文档与新设计不是同一架构

#### 影响判断

- 相关文档在代码重构阶段必须同步修订，避免新旧架构并存

## 4. 初始实施决策

- 决策 1：不尝试在旧 `WorkflowResult` 上演化出 session 语义，直接新建一套 session/playbook/agent-turn 契约
- 决策 2：不保留 `LangGraph` 作为兼容层，Python runtime 直接切到新入口
- 决策 3：不保留 `AssistantOrchestrationDto` 作为运行主路径；如 catalog 页面暂时仍引用旧对象，后续以新模型替换而不是桥接
- 决策 4：API runtime 模块按新领域边界拆分，避免继续向 `RuntimeService` 堆逻辑
- 决策 5：用户已明确要求“不要留下旧逻辑代码”，因此所有旧运行时主链路最终必须删除，而不是仅停止调用

## 5. 最终结论

- 本次重构主模型已切换完成
- 逐句审计 reopened 阶段识别出的结构性缺口已全部关闭：
  - worker 主动持久化 `session / session_event / playbook_run`
  - `STEP -> sandbox`
  - `TOOL_TASK -> runtime capability`
  - `RUN_PLAYBOOK` input schema 校验
  - activity retry 与 playbook timeout/retry policy 生效
  - runtime prompt context 按 memory window / 字节预算 / 最小暴露收口

## 6. 剩余工程化建议

- 若 API 未来扩展为多实例部署，需要把当前进程内 `session / conversation` 锁升级为共享锁设施
- `apps/web` 的 playbook 节点编辑目前以 JSON 形态为主，后续可升级为结构化编辑器
