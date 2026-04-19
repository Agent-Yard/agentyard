# Session Workflow 重构方案

## 1. 目标与概述

本方案将运行模型重构为：

- 一个 `session` 对应一条主 `Temporal workflow`
- assistant 显式配置唯一 `primaryAgentId`，作为默认主控 owner
- 运行时维护唯一 `currentOwnerAgentId`
- 支持受控的 `owner switch`
- 支持 owner 调用 `RUN_PLAYBOOK` 启动强流程 child workflow
- 完全移除 `LangGraph`

顶层会话控制由 `Temporal session workflow` 承担；强流程能力下沉为 `playbook`；agent 间协作通过显式动作协议完成。

### 1.1 两层能力模型

系统只有两层核心能力抽象：

- **Owner Agent**：会话主控角色，接收用户消息，执行推理，决定动作（回复 / 切换 owner / 启动 playbook）
- **Playbook**：强业务流程子任务，以 Temporal child workflow 形式执行，可挂起、可恢复、返回结构化结果

不引入独立的"cognitive tool"概念。轻量认知能力（如分类、摘要、提取）统一作为 agent 可调用的普通工具处理。

## 2. 核心设计决策

### 2.1 Owner 模型

- assistant 必须显式配置 `primaryAgentId`
- session 启动时 `currentOwnerAgentId = primaryAgentId`
- 运行时始终只有一个当前 owner
- 用户新消息默认投递给 `currentOwnerAgentId`
- `primaryAgentId` 仅用于 session 启动时的初始 owner

Owner 切换规则：

- 统一建模为 `SWITCH_OWNER(targetAgentId)`
- 任何当前 owner 均可发起切换，目标必须在该 owner 自身的 `switchableOwnerAgentIds` 白名单内
- 白名单为单向配置：A 允许切到 B 不代表 B 允许切回 A
- 切换时，当前用户消息连同会话上下文一并转交给新 owner，在同一个 agent turn 内继续推理
- 切换后，后续用户新消息进入新 owner
- 不存在自动回退机制；若需要回到先前 owner，由当前 owner 显式发起另一次 `SWITCH_OWNER`
- 单个 agent turn 内的连续切换次数上限由 `maxOwnerSwitchesPerTurn`（默认 `3`）控制；超限时本次切换不执行，由 session workflow 写入一条系统生成的降级 `OWNER_REPLY` 并结束 turn（避免再次调用 agent-runtime 造成无限回路）

### 2.2 Owner 可调用能力

Owner agent 可以返回以下控制动作（对应 `AgentDecision.action`）：

- **`REPLY`**：直接产出用户可见回复
- **`NO_REPLY`**：本 turn 不产生用户可见回复，仅结束推理回合。典型场景是 playbook 活跃期间 owner 判定当前用户消息无需即时回复（例如用户发送的是补充信息或闲聊），只需纳入上下文。不可携带 `accompanyingReply`
- **`SWITCH_OWNER`**：将会话控制权转交给另一个 agent，当前用户消息转交新 owner 处理
- **`RUN_PLAYBOOK`**：启动一个 playbook child workflow
  - `playbook workflow` 可以挂起、恢复、处理外部交互
  - `playbook workflow` 只返回结构化结果，由当前 owner 决定是否对用户回复
  - 必须作为 child workflow 执行，不允许内嵌执行
- **`SESSION_HUMAN_HANDOFF`**：将当前 session 切换到人工处理模式
  - 不结束当前 session workflow
  - 保留同一条 session event 流
  - 人工回复继续写入当前 session
  - handoff 期间禁用 agent 决策
  - 若已有 active playbook，则 playbook 继续执行，不被中断
  - playbook 当前状态、等待点和完成结果对人工侧可见

Agent 推理过程中可调用其配置的工具资源（包括知识库检索），但工具调用属于 agent-runtime 推理循环内部步骤，不作为 `AgentDecision` 返回给 session workflow，因此不出现在上述控制动作列表中。

`accompanyingReply` 是控制动作前的可选过渡回复。第一阶段支持以下动作携带：

- `SWITCH_OWNER`
- `RUN_PLAYBOOK`
- `SESSION_HUMAN_HANDOFF`

session workflow 在执行动作前先发送 `accompanyingReply`。典型场景：

- 切换 owner 时告知用户"正在为您转接 XX"
- 启动 playbook 时告知用户"正在为您处理，请稍候"

**Owner reevaluation**：由系统事件触发的一次 owner 决策回合（第一阶段仅由 playbook 进入 `SUCCEEDED` / `FAILED` / `CANCELLED` 终态触发，其他来源暂不开放）。它与用户消息触发的 agent turn 复用同一条推理接口，但输入上下文不包含新的用户消息，而是包含触发该回合的系统事件结果。同一个待处理的 reevaluation 触发只允许被消费一次：一旦 session workflow 决定开始执行该次 reevaluation，就必须先将对应的待处理标记视为已消费，后续无论该轮决策走正常分支还是校验失败降级分支，都不得再次触发同一来源的 reevaluation。

### 2.3 用户消息并发策略

消息处理采用分场景策略：

- **agent turn 执行期间**（owner 正在推理）：
  - 拒绝新消息
  - Update handler 返回 `BUSY` 状态
  - 前端据此禁用输入或提示"正在处理中"
- **playbook 活跃期间**（owner 已启动 playbook）：
  - owner 仍可接收用户消息
  - owner 自行决定如何处理：直接回复用户（`REPLY`），或返回 `NO_REPLY` 结束当前 turn（用户消息已写入 `USER_MESSAGE` 作为上下文供后续使用）
  - playbook 执行不受影响，不被中断
  - owner 的 system prompt 中注入当前 playbook 运行状态摘要
  - 禁止发起新的 `RUN_PLAYBOOK`
  - 禁止执行 `SWITCH_OWNER`
  - playbook 完成后，结果作为新的结构化事件并入当前 session 状态，由当前 owner 基于"当前会话状态 + playbook result"继续决策

### 2.4 Session 生命周期

- session 采用"空闲自动结束"策略
- 技术实现：
  - `idle timer`：workflow 空闲超时后正常结束
  - 不使用 `Continue-As-New`
  - 增加防御性 guardrail：限制单条 workflow 的生命周期与 history 增长；该 guardrail 只用于避免异常场景下的无界增长，不改变“空闲结束 + 新 session / 新 workflow” 的主路径
- 生命周期状态：
  - **active**：workflow 正在运行，可接收消息
  - **idle**：workflow 等待新消息中，idle timer 倒计时
  - **ended**：idle 超时，当前 session 与其 workflow 一并正常结束
- idle timer 暂停/恢复规则：
  - 以下任一条件为真时，idle timer 暂停（不计时、不到期）：
    - `agentTurnActive = true`
    - `activePlaybookRunId != null`
    - `sessionHumanHandoffActive = true`
  - 离开以上所有非空闲状态时（即 `agentTurnActive = false` 且 `activePlaybookRunId == null` 且 `sessionHumanHandoffActive = false`），idle timer 重置并开始倒计时
  - 设计动机：playbook 可能长时间挂起（等待 human task / 外部回调），handoff 期间由人工驱动，这些场景下 session 不应被空闲超时误杀
- workflow guardrail：
  - 由 `sessionPolicy.maxWorkflowAge`、`sessionPolicy.maxWorkflowHistoryEvents` 控制单条 workflow 的防御性上界
  - guardrail 命中时，session workflow 进入 `draining` 语义：不再接受新的用户消息 Update（返回 `REJECTED`）
  - `draining` 但 workflow 尚未结束时，API 不得为同一对话创建新的 session workflow；新消息只能被拒绝或由上层稍后重试，直到旧 workflow 真正结束
  - 若当前不处于以下任一状态（`agentTurnActive = true` / `activePlaybookRunId != null` / `sessionHumanHandoffActive = true`），则直接正常结束当前 workflow
  - 若 guardrail 命中时仍存在活跃 agent turn、active playbook 或 session handoff，则保留当前执行链路，但禁止继续接收新用户消息，并产出运维告警；待进入首个安全结束点后正常结束当前 workflow
  - 第一阶段不要求在活跃 playbook / handoff 中强制打断并迁移执行，只要求阻止继续无界增长并确保后续新消息不会持续堆积在同一条 workflow 上
- 新消息到达时：
  - API 必须以当前活跃 `sessionId` 为粒度对用户消息投递串行化，并在投递窗口内持有 session 级锁；同一活跃 session 的用户消息不允许并发执行”检查活跃 workflow / 投递消息”这条链路
  - 若当前无活跃 session（首次进入，或上一 session 已 idle 结束），”查找活跃 session / 创建新 session” 这条链路以更上层的对话绑定键（例如 `(userId + assistantId)` 或等价的用户-助手稳定键）加锁串行化，避免在 session 切换瞬间并发创建出多条 session
  - API 检查 session 是否有活跃 workflow
  - 有 → 通过 Update 投递
  - 没有 → 创建新 session 与新 workflow，并将该条消息作为新 session 的首条消息投递
- idle 结束后的语义：
  - idle 超时意味着当前 session 已结束，而不是等待后续“恢复”
  - 后续用户再次进入时，创建的是新的 session
  - 第一阶段不考虑跨 session 历史记忆自动回灌；若未来支持同用户长期记忆，也应以新的 session 上下文注入为准，而不是“重建旧 session”

### 2.5 Temporal 消息入口

- Temporal 入口采用 `Update + Signal` 混合模型
- 使用规则：
  - 用户新消息使用 `Update`（可返回结果或 BUSY 状态）
  - 人工恢复使用 `Signal`（触发 playbook 继续推进；是否产出用户可见消息取决于后续节点遍历结果与 owner reevaluation，不保证即时触达用户）
  - 外部回调使用 `Signal`（触发 playbook 继续推进；是否产出用户可见消息取决于后续节点遍历结果与 owner reevaluation，不保证即时触达用户）
  - Session handoff 解除使用 `Signal`（由人工侧通过 API 触发，API 内部投递 Signal 到 workflow）
- 并发到达规则：
  - Signal 与 Update 均按 Temporal 到达序处理
  - 用户消息入口在 API 侧已按 `sessionId` 串行化；理论上同一用户 / 同一 session 不存在并发消息投递到创建链路的情况
- Signal 校验与幂等原则：
  - 所有恢复类 Signal 都必须先命中正确的 session 与目标 run
  - `HUMAN_RESUME` 仅允许命中当前处于 `WAITING` 且 `waitingReason` 对应 `HUMAN_TASK` 的 playbook run
  - `EXTERNAL_CALLBACK` 仅允许命中当前处于 `WAITING` 且 `waitingReason` 对应 `EXTERNAL_INTERACTION` 的 playbook run
  - 若目标 run 已不在匹配的等待态，则该 Signal 视为过期或重复请求：不得再次推进状态机，不得重复恢复
  - `SESSION_HANDOFF_END` 定义为幂等 Signal：若当前已不在 handoff，则忽略或仅记录幂等命中，不重复写 `SESSION_HUMAN_HANDOFF_ENDED`
  - 第一阶段对无效 / 过期 / 重复 Signal 的处理要求是：拒绝推进状态机；可记录诊断日志或事件，但不要求为每一种失败单独设计事件类型
- Update handler 返回契约：
  - 成功接收：返回 `ACCEPTED`
  - agent turn 执行中：返回 `BUSY`
  - workflow 正在结束或处于 `draining`：返回 `REJECTED`（API 不得在旧 workflow 未结束前创建新的 session workflow；上层应稍后重试）

## 3. 运行时架构

### 3.1 Session Workflow

`session workflow` 负责：

- 管理 session 长期状态（owner、sharedState、playbook 运行状态）
- 接收 workflow 入口事件（Update / Signal），并将相关产物落盘为 session event（持久化事件流，定义见 §6.1）
- 按 `currentOwnerAgentId` 驱动当前 owner 执行
- 执行 owner 切换
- 启动 playbook child workflow
- 异步处理 playbook child workflow 的完成通知，不阻塞 session 事件循环
- playbook 进入任一终态时写入 `PLAYBOOK_COMPLETED`，若未处于 session human handoff 则触发 owner reevaluation（终态分类与 handoff 期间的处理详见 §3.3）
- 处理人工恢复和外部回调
- 维护 idle timer
- 管理 agent turn 期间的消息拒绝

### 3.2 Agent Runtime

`agent-runtime` 是无状态执行服务，职责为：

- 执行单个 agent 的一轮推理
- 在推理循环内部处理工具调用和知识库检索
  - 知识库检索能力由 runtime 通过内部接口远程调用 `knowledge-service` 完成
  - 不要求把 `knowledge-service` 的存储层、导入链路、索引构建链路整体并入 runtime
- 在推理循环内部读写 `sharedState`（工具调用结果、中间推理产物、用户偏好摘要等认知性上下文可即时写入）
- 返回结构化决策结果和更新后的 `sharedState` 给 session workflow

对外接口签名：`(AgentConfig, LlmInputEnvelope, SharedState) → AgentTurnResult`

其中 `LlmInputEnvelope` 采用混合式输入模型：

- `PromptInstruction`
  - 低频变化
  - 以稳定前缀形式承载全局平台约束、输出 schema、决策规则、session state patch 语义
  - 目标是跨 turn 尽量复用，提升缓存命中率
- `PromptRuntimeMessages`
  - 高频变化
  - 使用 LLM 原生多条 messages 构建运行时上下文，而不是先拼成单一大文本块
  - 主要承载：
    - 当前触发源（用户消息 / playbook 完成 / 其他系统事件）
    - 当前用户消息或当前系统事件结果
    - 会话记忆窗口
    - `sharedState` 中可暴露给当前 agent 的认知性上下文（`facts` / `artifacts` / 当前 agent 的 `agentScope`）
    - 最近工具结果 / 恢复输入等临时运行时信息
  - 目标是更充分利用聊天模型的原生会话能力，同时将高频变化内容局限在消息尾部
- `PromptCapabilities`
  - 不再等同于“完整文本块 prompt”
  - 按能力类型拆分：
    - `Tools / Functions`
      - 优先对齐模型原生 tool/function calling 能力
      - 工具目录、参数 schema、返回 schema 以模型原生 function/tool 定义形式提供，而不是主要依赖自由文本说明
    - `Skills`
      - 继续作为文本能力层，而不是 function
      - 采用“目录先暴露、详情按需加载”的模式：先提供技能目录，由模型显式请求 `skillReads` 后再把对应技能详情注入提示上下文
    - `Routes`
      - 当前节点可用路由仍作为稳定能力说明的一部分提供给模型，用于最终决策选择

术语约束：

- 文档中的 `ConversationContext` 若用于描述 LLM 输入，统一收敛为 `PromptRuntimeMessages`
- 完整 LLM 输入不再以 `ConversationContext` 泛称，而由 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities` 共同构成
- 对 tools 的首选建模是模型原生 function/tool calling；对 skills 的首选建模是按需加载的文本能力
- 该拆分的首要目标之一是缓存友好：稳定前缀尽量固定，高频变化内容尽量压缩在 runtime messages 尾部

provider role / tool calling 适配原则：

- 各 LLM provider 对原生 message role 并不统一，因此内部实现不应直接绑定某一家 provider 的 role 集合
- `agent-runtime` 内部应先以语义层抽象输入元素，再由 provider adapter 映射到具体模型协议。建议的内部语义层至少包括：
  - `instruction`
  - `user_turn`
  - `assistant_turn`
  - `system_event`
  - `tool_definition`
  - `tool_call`
  - `tool_result`
- provider adapter 负责将上述语义层映射到具体 provider 的原生 role / content block / function calling 协议
- 第一阶段实现约束：
  - 仅实现 OpenAI-compatible provider 映射
  - `PromptRuntimeMessages`、原生 tool/function calling、tool result 注入等行为，第一阶段都以 OpenAI-compatible 接口语义为准
  - Anthropic、Gemini 等其他 provider 的 role / tool calling 差异仅在抽象层预留，不在第一阶段落地

`AgentTurnResult` 结构：

- `decision`：结构化决策（`AgentDecision`）
- `sharedState`：推理循环结束时的 `sharedState` 全量快照；session workflow 接收后以该快照整体替换 session 当前的 `sharedState`

`AgentDecision` 结构：

- `action`：决策类型
  - `REPLY`：回复用户
  - `NO_REPLY`：本 turn 不产生用户可见回复
  - `SWITCH_OWNER`：请求切换 owner，当前用户消息转交新 owner
  - `RUN_PLAYBOOK`：请求启动 playbook
  - `SESSION_HUMAN_HANDOFF`：请求会话级人工接管
- `replyContent`（可选）：`REPLY` 时必填，用于生成 `OWNER_REPLY` 事件内容；其他 action 不得携带
- `targetAgentId`（可选）：`SWITCH_OWNER` 时必填，为目标 owner agent 的 `agentId`；其他 action 不得携带
- `playbookId`（可选）：`RUN_PLAYBOOK` 时必填，为目标 playbook 的 `playbookId`；其他 action 不得携带
- `playbookInput`（可选）：`RUN_PLAYBOOK` 时必填，必须符合目标 playbook 的 `inputSchema`；其他 action 不得携带
- `accompanyingReply`（可选）：控制动作前的附带回复消息，`SWITCH_OWNER`、`RUN_PLAYBOOK`、`SESSION_HUMAN_HANDOFF` 时可携带，session workflow 在执行动作前先发送给用户；`REPLY` / `NO_REPLY` 不允许携带

字段与 action 的对应关系由 §5.2 通用校验兜底：凡是出现"应携带字段缺失"或"不允许携带字段被填充"的情况，一律按校验失败走统一降级路径。

`sharedState` 读写语义：

- 入参的 `sharedState` 是 session 在本次 turn 开始时的快照
- `sharedState` 只用于承载 agent 自主维护的认知性会话上下文，例如工具结果摘要、用户偏好、阶段性理解、后续回复需要复用的非权威笔记
- `sharedState` 不得承载操作性状态：不能作为 owner 路由、权限判断、playbook 生命周期、handoff 状态、外部回调处理、业务推进条件的权威依据
- 所有影响系统行为的操作性状态，必须由 session workflow 状态、session event、playbook run 或其他显式结构化模型承载
- agent-runtime 在本次推理循环内可自由读写 `sharedState`（工具结果写入、派生字段计算等）
- 推理循环结束时的最终值作为 `AgentTurnResult.sharedState` 返回
- session workflow 仅在成功接收返回值后整体替换；推理过程中的中间态对其他 session 事件不可见
- 推理循环中途失败时，session workflow 保留 turn 开始前的旧快照，丢弃未提交的写

Agent-runtime activity 异常处理：

- activity 内置重试策略，按 retry policy 重试推理直至成功或耗尽
- 重试期间 `sharedState` 保持 turn 开始前的旧快照；每次重试都从该快照重新执行，不会叠加部分写
- 重试耗尽后 activity 最终失败，session workflow 按下列路径收尾：
  - 丢弃本次 turn 的所有中间产物（未提交 `sharedState`、未写事件）
  - 写入 `AGENT_TURN_FAILED` 事件（payload 含失败原因、actor agentId、是否为 reevaluation 触发源等诊断信息）
  - 由 session workflow 生成一条降级 `OWNER_REPLY`（用户可见措辞对外统一为"当前处理遇到问题，请稍后再试"，不暴露内部错误细节）
  - 设置 `agentTurnActive = false`，执行 turn 结束通用逻辑（§5.1）
  - 不再自动重试本次 turn；用户可通过新消息重新驱动
- 若失败的是 reevaluation（由 playbook 终态触发），同样走上述降级路径；`pendingOwnerReevaluation` 已在启动前被消费，失败后不得恢复

`PromptRuntimeMessages` 组装原则：

- 只放当前轮推理需要的运行时信息，不复制 `PromptInstruction` / `PromptCapabilities` 已表达的稳定内容
- 当前用户消息或当前系统事件结果必须位于 runtime messages 的最新位置，保证本轮触发语义清晰
- 历史消息以原生 messages 形式保留窗口内最近若干条，并受字节预算限制
- 工具结果以消息或 provider 支持的 tool result 形式注入，只保留近期结果，并受字节预算限制
- `sharedState` 暴露时按 `facts` / `artifacts` / 当前 agent 的 `agentScope` 分区组织，但只暴露决策需要的最小子集
- 目标不是完整还原历史，而是提供足够决策所需的最小运行时上下文
- 第一阶段按 OpenAI-compatible message 语义实现；后续若支持其他 provider，由 provider adapter 负责转换，不改变上层抽象

工具调用在 agent-runtime 推理循环内部完成，不作为 `AgentDecision` 返回给 session workflow。

### 3.3 Playbook Workflow

Playbook 以 Temporal child workflow 形式执行，采用混合编排模型：

- **Temporal workflow（Java）负责流程控制**：持有图结构，按 `entryNodeKey → edges → next node` 遍历，管理路由决策（简单条件判断）、挂起/恢复、超时/重试
- **节点执行分层**：
  - `STEP`：执行“配置即代码”的脚本化节点；workflow 不直接执行节点代码，而是通过 activity 将版本化的节点配置/脚本和当前运行时数据送入受限沙箱环境执行，接收结构化结果后再继续后续路由
  - `TOOL_TASK`：通过 activity 调用对应工具或运行时能力
- **Temporal 原生能力负责等待**：`HUMAN_TASK` / `EXTERNAL_INTERACTION` 使用 `Workflow.await()` 挂起，Signal 到达后恢复

因此，Temporal workflow 的确定性边界固定为：仅负责图遍历、状态转移、等待与路由；所有脚本执行、沙箱求值和外部能力调用都必须发生在 activity 边界之外。workflow 内部只消费节点定义、输入数据、activity 返回结果与显式路由条件，不直接执行动态脚本。

Playbook run 状态流转：

- 启动时 → `status = RUNNING`，`waitingReason = null`
- 进入 `HUMAN_TASK` / `EXTERNAL_INTERACTION` 节点并调用 `Workflow.await()` 前 → `status = WAITING`，`waitingReason` 按节点类型设置（如 `human_task:<nodeKey>` 或 `external_interaction:<nodeKey>`），并向 session event 写入 `PLAYBOOK_WAITING`
- 对应 Signal 到达并唤醒 `Workflow.await()` 后 → `status = RUNNING`，清空 `waitingReason`，并向 session event 写入 `PLAYBOOK_RESUMED`
- 进入终态时 → 按下方终态处理切换至 `SUCCEEDED` / `FAILED` / `CANCELLED`

状态更新由 playbook child workflow 通过持久化 activity 写入 `playbook_run` 记录。对于外部可感知的关键等待点（进入等待、恢复执行、终态完成），同时写入 session event，用于补齐 append-only 时间线；不要求把 playbook 内部每个节点流转全部事件化。

Playbook 进入任一终态（`SUCCEEDED` / `FAILED` / `CANCELLED`）时：

- 写入 `PLAYBOOK_COMPLETED` 事件（payload 含 `status`、可选 `failureReason`）
- 更新对应 playbook run 的 `status`、`result` 或 `failureReason`
- 清空 session workflow 的 `activePlaybookRunId`
- 若未处于 session human handoff，则触发一次 owner reevaluation（成功/失败/取消统一入口，由 owner 决策后续动作）
- 若处于 session human handoff，则终态直接暴露给人工侧处理，不触发 owner reevaluation；该终态在 handoff 期间只作为人工决策所需信息与后续可见历史上下文保留，handoff 结束后也不会对其补偿触发自动 reevaluation
- 不直接产出用户可见回复

## 4. 配置模型

### 4.1 Assistant 配置

`assistant` 负责 session 级主控规则。

- `primaryAgentId`
- `ownerPolicy`
  - `maxOwnerSwitchesPerTurn = 3`（单个 agent turn 内允许的连续切换次数上限）
- `sessionPolicy`
  - `idleTimeout`
  - `maxWorkflowAge`（防御性上界；超过后 workflow 进入 draining）
  - `maxWorkflowHistoryEvents`（防御性上界；超过后 workflow 进入 draining）
- `replyPolicy`
  - 用户可见回复统一由当前 owner 产出
- `playbookPolicy`
  - 默认超时/重试策略

### 4.2 Agent 配置

`Agent` 表示 owner 级会话角色。

- 基本信息
  - `agentId`
  - `name`
  - `role`
  - `responsibility`
- 推理与资源
  - `modelResourceId`
  - `systemPrompt`
  - `knowledgeEnabled / knowledgeBaseId`
  - `memoryWindowSize`
  - `skillResourceIds`
  - `toolResourceIds`
- 会话控制能力
  - `canOwnSession`
  - `allowedActions`（取值来自 `AgentDecision.action` 枚举，用于白名单限制 agent 可发起的控制动作）
  - `switchableOwnerAgentIds`
  - `playbookIds`

约束：

- `owner` = `Agent + canOwnSession = true`
- session 启动时 owner 必须来自 assistant 的 `primaryAgentId`
- 只有当前 owner 可以发起 `SWITCH_OWNER`

### 4.3 Playbook 配置

`playbook` 是被 owner 调用的强业务流程单元，可挂起、可恢复、返回结构化结果。

- 基本信息
  - `playbookId`
  - `name`
  - `description`
- 输入输出契约
  - `inputSchema`
  - `resultSchema`
- 执行策略
  - `timeoutPolicy`
  - `retryPolicy`
  - `allowHumanTask = true`
  - `allowExternalInteraction = true`
- 流程定义
  - `entryNodeKey`（显式入口）
  - `nodes`
  - `edges`

### 4.4 Playbook 节点类型

节点公共字段：

- `nodeKey`
- `nodeName`
- `nodeType`
- `description`

节点类型：

- `STEP`
  - 本质是“配置即代码”的脚本化节点
  - 用于输入校验、状态映射、路由前处理、轻量业务逻辑计算等
  - 执行时将版本化节点配置/脚本与当前节点输入一起送入受限沙箱环境执行
  - 由 activity 调度执行，workflow 不直接运行节点脚本
  - 必须具备显式版本标识（如 `scriptRef`、`scriptVersion` 或等价机制），确保节点行为可审计、可回放定位
  - 输出为结构化结果，供后续路由与状态更新使用
- `TOOL_TASK`
  - 显式调用固定工具（含需要 LLM 的工具）
  - 工具选择和参数映射由 playbook 决定，不交给模型临场决定
- `HUMAN_TASK`
  - 进入人工等待点
  - 生成待办并挂起 playbook workflow
  - 人工恢复 Signal 到达后继续执行
- `EXTERNAL_INTERACTION`
  - 发起站外交互
  - 挂起 playbook workflow
  - 等待回调 Signal 到达后继续执行
- `END`
  - 结束 playbook，输出结构化结果

节点边配置：

- `edgeKey`
- `sourceNodeKey`
- `targetNodeKey`
- `routeKey`
- `label`
- `defaultEdge`

## 5. Session Workflow 内部状态

主 session workflow 维护以下状态：

- `sessionId`
- `assistantId`
- `assistantReleaseVersion`（session 启动时绑定的 assistant 配置版本，用于回放和调试）
- `primaryAgentId`
- `currentOwnerAgentId`
- `ownerSwitchCountInTurn`（当前 agent turn 内已发生的切换次数，turn 开始时清零，超过 `maxOwnerSwitchesPerTurn` 时拒绝再次切换）
- `sharedState`（扁平 `Map<String, Object>`，全量可读可写，agent 间通过 key 命名约定隔离；仅用于认知性会话上下文，不用于承载操作性状态）
- `activePlaybookRunId`（当前活跃 playbook 的 runId；初始 `null`，启动 playbook 时设置，playbook 进入任一终态 `SUCCEEDED` / `FAILED` / `CANCELLED` 时清空；为 `null` 时允许发起新的 `RUN_PLAYBOOK`）
- `agentTurnActive`（为 `true` 时 Update handler 拒绝新消息）
- `sessionHumanHandoffActive`（为 `true` 时禁用 agent 决策，仅允许人工回复）
- `pendingOwnerReevaluation`（playbook 结果到达但当前已有 agent turn 在执行时置为 `true`；由于第一阶段同一时刻至多一个活跃 playbook，布尔值即可表达待处理状态；该标记是一次性触发语义：turn 结束逻辑一旦决定启动对应的 reevaluation，就必须先清空该标记，确保同一来源的 reevaluation 不会被重复执行；进入 session human handoff 时清空）
- `idleDeadline`

### 5.1 Turn 结束通用逻辑

每次 `agentTurnActive` 从 `true` 转为 `false` 时，session workflow 统一执行以下步骤：

1. 重置 `ownerSwitchCountInTurn = 0`（确保下一个 turn 从干净计数起步，避免跨 turn 泄漏）
2. 若 `sessionHumanHandoffActive = true`，turn 结束，不进入后续步骤
3. 若 `pendingOwnerReevaluation = true`，立即触发 reevaluation：先清空 `pendingOwnerReevaluation`（将该次待处理触发标记为已消费），再设置 `agentTurnActive = true`，调用 agent-runtime 进行 owner reevaluation；reevaluation 完成后再次将 `agentTurnActive` 置为 `false`，重新进入本通用逻辑。若该轮 reevaluation 走到校验失败统一降级路径，也视为该次待处理触发已处理完成，不得恢复旧标记或再次触发同一 reevaluation
4. 否则 turn 正常结束

此逻辑适用于所有 turn 结束场景（§9.1 step 12、§9.2 step 8、§9.3 step 5 / step 12、§9.4 step 4、reevaluation 自身完成后）。

### 5.2 决策校验与降级

session workflow 收到 agent 返回的 `AgentDecision` 后，先执行运行时校验，所有控制动作共享一条统一降级路径。校验在 `sharedState` 快照替换之后、§9.1 step 10 分支之前执行。

通用校验（所有 action）：

- `decision.action ∈ currentOwner.allowedActions`
- `AgentDecision` 字段形态与 `action` 匹配：`REPLY` 必填 `replyContent`；`SWITCH_OWNER` 必填 `targetAgentId`；`RUN_PLAYBOOK` 必填 `playbookId` 和 `playbookInput`；`SESSION_HUMAN_HANDOFF` / `NO_REPLY` 不得携带上述专属字段；`accompanyingReply` 的携带范围遵循 §3.2 定义

`SWITCH_OWNER` 专属校验：

- `targetAgentId` 存在且未被删除
- `targetAgentId ∈ currentOwner.switchableOwnerAgentIds`
- 目标 agent `canOwnSession = true`
- `activePlaybookRunId == null`（playbook 活跃期间禁止 `SWITCH_OWNER`，见 §2.3 / §9.3 / §10）
- `ownerSwitchCountInTurn < maxOwnerSwitchesPerTurn`

`RUN_PLAYBOOK` 专属校验：

- `playbookId` 存在且未被删除
- `playbookId ∈ currentOwner.playbookIds`
- `activePlaybookRunId == null`（playbook 活跃期间不允许再次启动，见 §10）
- `playbookInput` 符合 playbook 的 `inputSchema`

`SESSION_HUMAN_HANDOFF` 专属校验：无专属校验项；但存在一条幂等短路规则——若 `sessionHumanHandoffActive = true`，视为幂等请求，走"幂等路径"而非"降级路径"：不写 `AGENT_DECISION_REJECTED`、不生成降级 `OWNER_REPLY`；`accompanyingReply` 仍照常作为 `OWNER_REPLY` 写入，随后设置 `agentTurnActive = false`，执行 turn 结束通用逻辑（§5.1）。

`REPLY` / `NO_REPLY`：仅通用校验

校验失败统一降级路径（不含上述 `SESSION_HUMAN_HANDOFF` 幂等短路）：

- 校验发生在 `sharedState` 快照替换之后，因此校验失败不回滚本 turn 已提交的 `sharedState`；仅拒绝本次控制动作本身
- 丢弃本次决策（含 `accompanyingReply`，避免落地与拒绝语义冲突的回复）
- 写入 `AGENT_DECISION_REJECTED` 事件（payload 含 `action`、`rejectReason`、原始参数摘要），用于诊断
- 由 session workflow 生成一条降级 `OWNER_REPLY`（用户可见措辞对外统一为"当前无法完成该操作，请稍后再试"，不暴露内部规则细节）
- 设置 `agentTurnActive = false`，执行 turn 结束通用逻辑（§5.1）
- 不再回调 agent-runtime，避免校验失败引发无限决策回路

校验通过后按 §9.1 step 10 的分支推进到对应子流程。Agent-runtime 侧的动作生成应遵循同一组白名单以减少校验拒绝频次，但 session workflow 始终作为权威校验点兜底。

## 6. 数据模型

### 6.1 Session Event

`session event` 字段：

- `eventId`
- `sessionId`
- `sequence`
- `eventType`
- `createdAt`
- `actorType`
- `actorId`
- `payload`
- `relatedPlaybookRunId`
- `relatedOwnerAgentId`

事件类型：

- `USER_MESSAGE`（用户输入；payload 含消息文本及可选富内容）
- `OWNER_REPLY`（用户可见回复；payload 含消息文本及可选富内容；按 `actorType` 区分来源：`actorType = agent` 为 owner 正常回复，`actorType = system` 为 session workflow 生成的降级回复）
- `HUMAN_OPERATOR_REPLY`（handoff 期间人工回复；payload 含消息文本及可选富内容；`actorType = human_operator`、`actorId` 为操作员标识）
- `AGENT_DECISION_REJECTED`（运行时校验失败时写入；payload 含 `action`、`rejectReason`、原始参数摘要）
- `AGENT_TURN_FAILED`（agent-runtime activity 耗尽重试后失败时写入；payload 含失败原因、actor agentId、是否为 reevaluation 触发源等诊断信息）
- `OWNER_SWITCH`
- `PLAYBOOK_STARTED`
- `PLAYBOOK_WAITING`（playbook 进入外部可感知等待点；payload 含 `runId`、`nodeKey`、`waitingType`（`HUMAN_TASK` / `EXTERNAL_INTERACTION`）、`waitingReason`）
- `PLAYBOOK_RESUMED`（playbook 从等待点恢复；payload 含 `runId`、`nodeKey`、`resumeSource`（`HUMAN` / `EXTERNAL_SYSTEM`）和可选恢复摘要）
- `PLAYBOOK_COMPLETED`（playbook 进入任一终态；payload 含 `status`（`SUCCEEDED` / `FAILED` / `CANCELLED`）和可选 `failureReason`）
- `SESSION_HUMAN_HANDOFF_STARTED`
- `SESSION_HUMAN_HANDOFF_ENDED`
- `HUMAN_RESUME_RECEIVED`（session workflow 收到人工恢复 Signal 时写入，payload 含恢复目标的 playbook runId 和恢复数据）
- `EXTERNAL_CALLBACK_RECEIVED`（session workflow 收到外部回调 Signal 时写入，payload 含对应的 playbook runId 和回调数据）

### 6.2 Playbook Run

`playbook run` 记录 playbook 执行实例，字段：

- `runId`
- `sessionId`
- `parentSessionEventId`（触发本次 playbook 启动的 session event id，对应 `PLAYBOOK_STARTED` 事件的 `eventId`，用于回溯与审计）
- `playbookId`
- `ownerAgentId`
- `status`（取值：`RUNNING` / `WAITING` / `SUCCEEDED` / `FAILED` / `CANCELLED`）
- `input`（对应 `AgentDecision.playbookInput` 在本次运行的持久化记录）
- `result`
- `failureReason`（`status = FAILED` 时的失败原因）
- `createdAt`
- `updatedAt`
- `waitingReason`

## 7. 对外接口

API 入口：

- 向 session workflow 提交用户消息
- 查询 session 当前状态
- 查询 session event 列表
- 查询 playbook run 列表
- 提交人工恢复
- 提交外部回调
- 提交 session handoff 解除

Session 观测以 `session event` 为主，playbook 观测以 `playbook run` 为主。

## 8. 模块架构

### 8.1 模块划分

| 模块 | 技术栈 | 职责 |
|------|--------|------|
| `apps/api` | Java Spring Boot | 控制面 API、目录管理、持久化投影、SSE 推送 |
| `apps/worker` | Java Temporal | session workflow 状态机、playbook child workflow 编排、activity 调度 |
| `apps/agent-runtime` | Python FastAPI | 单 agent 单轮推理、工具调用、通过内部接口远程调用 knowledge-service 完成在线知识检索 |
| `apps/knowledge-service` | Python FastAPI | 知识库导入、切片、索引快照构建、检索与 chunk read 数据接口 |
| `apps/web` | Vue | 前端控制台 |
| `packages/contracts-jvm` | Java | JVM 侧共享契约 |
| `packages/contracts` | TypeScript | 前端共享契约 + OpenAPI |

### 8.2 Worker 内部结构

```
apps/worker/
  └── workflow/
      ├── session/                     # Session workflow 主状态机
      │   ├── SessionWorkflow.java
      │   ├── OwnerRouter.java
      │   └── MessageHandler.java
      ├── playbook/                    # Playbook child workflow
      │   ├── PlaybookWorkflow.java
      │   └── NodeDispatcher.java
      └── activity/                    # Temporal activities
          ├── AgentTurnActivity.java   # 调 agent-runtime 执行推理
          ├── PlaybookStepActivity.java # 调受限沙箱执行 STEP 节点脚本
          └── PlaybookToolActivity.java # 调工具或运行时能力
```

## 9. 典型执行流程

### 9.1 普通消息处理

1. 用户发消息
2. API 按 §2.4 定义的粒度加锁：有活跃 session 时以 `sessionId` 粒度串行化投递；无活跃 session 时以对话绑定键（`(userId + assistantId)` 或等价键）粒度串行化"查找/创建 session"链路
3. API 检查 session 是否有活跃 workflow
   - 有 → 通过 `Update` 投递
   - 没有 → 创建新 session 与新 workflow，并将该条消息作为新 session 的首条消息投递
4. 投递完成后释放本次投递锁
5. Update handler 准入检查：
   - `agentTurnActive = true` → 返回 `BUSY`，前端提示用户，流程终止
   - 否则接受消息，继续后续步骤
6. 消息写入 `session event`（`USER_MESSAGE`）
7. 分流：
   - `sessionHumanHandoffActive = true` → 消息由人工侧处理（详见 §9.4），流程终止，不触发 agent turn
   - 否则设置 `agentTurnActive = true`，消息交给 `currentOwnerAgentId`（调 agent-runtime activity）
8. owner 返回 `AgentTurnResult`，session workflow 用其中的 `sharedState` 快照整体替换当前 session 的 `sharedState`
9. session workflow 执行运行时校验与降级（§5.2）
   - 校验失败 → 走统一降级路径，流程终止
   - 校验通过 → 继续 step 10
10. session workflow 按 `decision.action` 分支执行：
   - `REPLY` → 继续 step 11
   - `NO_REPLY` → 跳过 step 11，直接进入 step 12（本 turn 不写 `OWNER_REPLY`）
   - `SWITCH_OWNER` / `RUN_PLAYBOOK` / `SESSION_HUMAN_HANDOFF` → 分别走 §9.2 / §9.3 / §9.4
11. `REPLY` 分支：回复以 `OWNER_REPLY` 事件写入事件流
12. 设置 `agentTurnActive = false`，执行 turn 结束通用逻辑（§5.1）

### 9.2 Owner 切换

1. 当前 owner 返回 `AgentDecision(SWITCH_OWNER, targetAgentId, accompanyingReply?)`
2. `SWITCH_OWNER` 的目标存在性、白名单、`canOwnSession`、单活约束、切换次数上限等校验统一由 §5.2 处理；本节仅描述校验通过后的切换流程
3. 若有 `accompanyingReply`，先发送给用户（写入 `OWNER_REPLY`）
4. 保持 `agentTurnActive = true`，因为当前用户消息仍在处理链路中
5. 切换 `currentOwnerAgentId`，`ownerSwitchCountInTurn += 1`，写入 `OWNER_SWITCH`
6. 将当前用户消息连同会话上下文交给新 owner 处理；新 owner 可以继续切换、回复、发起 playbook 等
7. 后续用户新消息进入新 owner
8. 当前消息处理链产出终态（REPLY / NO_REPLY / RUN_PLAYBOOK / SESSION_HUMAN_HANDOFF）后，按对应分支流程推进，最终 turn 结束时设置 `agentTurnActive = false`，并执行 turn 结束通用逻辑（§5.1，包含 `ownerSwitchCountInTurn` 重置）

### 9.3 Playbook 执行

1. 当前 owner 返回 `AgentDecision(RUN_PLAYBOOK, playbookId, playbookInput, accompanyingReply?)`
2. `RUN_PLAYBOOK` 的存在性、调用白名单、单活约束、输入 schema 等校验统一由 §5.2 处理；本节仅描述校验通过后的执行流程
3. 若有 `accompanyingReply`，先发送给用户（写入 `OWNER_REPLY`）
4. session workflow 成功发起 playbook child workflow，设置 `activePlaybookRunId = runId`，写入 `PLAYBOOK_STARTED`
5. child workflow 启动后，session workflow 设置 `agentTurnActive = false`，owner 当前回合结束，执行 turn 结束通用逻辑（§5.1）
6. playbook workflow 在 Temporal 侧遍历图节点：
   - `STEP` → 通过 activity 将版本化节点脚本与当前输入送入受限沙箱执行，接收结构化结果后继续路由（详见 §3.3 / §4.4）
   - `TOOL_TASK` → 调 activity 执行工具或运行时能力
   - `HUMAN_TASK` → 更新 `playbook_run.status = WAITING`、`waitingReason = human_task:<nodeKey>`，写入 `PLAYBOOK_WAITING`，`Workflow.await()` 挂起等待 `HUMAN_RESUME` Signal；Signal 到达并唤醒后恢复 `status = RUNNING`、清空 `waitingReason`，并写入 `PLAYBOOK_RESUMED`
   - `EXTERNAL_INTERACTION` → 更新 `playbook_run.status = WAITING`、`waitingReason = external_interaction:<nodeKey>`，写入 `PLAYBOOK_WAITING`，`Workflow.await()` 挂起等待 `EXTERNAL_CALLBACK` Signal；Signal 到达并唤醒后恢复 `status = RUNNING`、清空 `waitingReason`，并写入 `PLAYBOOK_RESUMED`
7. playbook 执行期间，owner 仍可接收用户消息并回复，但禁止再发起 `RUN_PLAYBOOK` 或 `SWITCH_OWNER`
8. playbook 进入任一终态（`SUCCEEDED` / `FAILED` / `CANCELLED`）时：
   - 更新对应 playbook run 的 `status`，成功时写入 `result`，失败时写入 `failureReason`
   - 写入 `PLAYBOOK_COMPLETED` 事件（payload 含 `status`、可选 `failureReason`）
   - 清空 `activePlaybookRunId = null`
9. 若 `sessionHumanHandoffActive = true`，终态直接暴露给人工侧处理，不触发 owner reevaluation，流程终止（对应 §9.4 step 9）
10. 否则（非 handoff 状态），按 `agentTurnActive` 决定触发方式：
   - `agentTurnActive = false` → session workflow 设置 `agentTurnActive = true` 并立即触发一次 owner reevaluation
   - `agentTurnActive = true` → 设置 `pendingOwnerReevaluation = true`，由 turn 结束通用逻辑触发（详见 §5.1）；该标记一旦被 §5.1 step 3 消费，不得因 reevaluation 内部的校验失败或降级而恢复
11. reevaluation 触发后复用与普通 turn 相同的链路：调 agent-runtime 推理得到 `AgentTurnResult` → 按 §9.1 step 8 执行 `sharedState` 快照替换 → 按 §5.2 执行运行时校验与降级 → 按 §9.1 step 10 的分支处理 `decision.action`。区别仅在于推理的触发源是 playbook 终态而非新的用户消息；reevaluation 期间 owner 也可返回 `REPLY` / `NO_REPLY` / `SWITCH_OWNER` / `RUN_PLAYBOOK` / `SESSION_HUMAN_HANDOFF`，由对应子流程各自推进
12. reevaluation 最终通过 §9.1 step 12（或其他子流程里的 turn 结束点）把 `agentTurnActive` 置为 `false` 并执行 turn 结束通用逻辑（§5.1）；若本次 reevaluation 是由 `pendingOwnerReevaluation` 触发，则其待处理标记已在启动前被消费，此处不再重复清理

> **终态来源**：`SUCCEEDED` 由 playbook 正常到达 `END` 节点产生；`FAILED` 由 child workflow 抛异常或超时产生（`timeoutPolicy` / `retryPolicy` 用尽后）；`CANCELLED` 由父 session workflow 结束或其他内部机制触发。第一阶段不开放主动取消 playbook 的 API 入口。

### 9.4 Session Human Handoff

1. 当前 owner 返回 `AgentDecision(SESSION_HUMAN_HANDOFF, accompanyingReply?)`
2. 若有 `accompanyingReply`，先发送给用户（写入 `OWNER_REPLY`）
3. session workflow 设置 `sessionHumanHandoffActive = true`，清空 `pendingOwnerReevaluation`（若有积压的 reevaluation，因人工接管已生效而作废；playbook 结果仍以 `PLAYBOOK_COMPLETED` 事件保留在事件流中，对人工可见）
4. session human handoff 生效后，设置 `agentTurnActive = false`，owner 当前回合结束，执行 turn 结束通用逻辑（§5.1）
5. 写入 `SESSION_HUMAN_HANDOFF_STARTED`
6. 若当前已有 `activePlaybookRunId`，则 playbook 继续执行；其运行状态、等待点和完成结果对人工侧可见

handoff 期间行为：

7. 用户消息继续进入同一 session，写入 `USER_MESSAGE`
8. handoff 期间不再触发 agent 决策，人工回复写入 `HUMAN_OPERATOR_REPLY` 事件
9. playbook 在 handoff 期间完成时，结果直接展示给人工侧处理，不触发 owner reevaluation；其结果只作为人工决策输入与后续可见历史上下文保留

解除流程：

10. 人工侧通过 API 提交 handoff 解除，API 内部向 workflow 投递 Signal
11. workflow 收到 Signal 后先检查 `sessionHumanHandoffActive`
12. 若当前仍在 handoff，则设置 `sessionHumanHandoffActive = false`，写入 `SESSION_HUMAN_HANDOFF_ENDED`
13. 若当前已不在 handoff，则该 Signal 按幂等命中处理：不重复写 `SESSION_HUMAN_HANDOFF_ENDED`，流程终止
14. session 恢复 agent 决策；handoff 期间的 playbook 终态、人工回复与人工决策过程均视为历史上下文，不会在 handoff 结束时自动补偿触发 owner reevaluation，后续仅在新的用户消息或新的系统事件到达时进入新的 owner 决策回合

### 9.5 人工恢复与外部回调

用于唤醒 `WAITING` 状态的 playbook run（由 `HUMAN_TASK` 或 `EXTERNAL_INTERACTION` 节点产生）。

1. 外部端（人工系统 / 外部服务）通过 API 提交恢复/回调请求，payload 含目标 playbook `runId` 和恢复数据
2. API 校验 runId 属于当前 session，将请求作为 Signal 投递到对应 session workflow
3. session workflow 收到 Signal 后先做运行时校验：
   - 人工恢复必须命中 `status = WAITING` 且 `waitingReason` 对应 `HUMAN_TASK`
   - 外部回调必须命中 `status = WAITING` 且 `waitingReason` 对应 `EXTERNAL_INTERACTION`
   - 若不满足，则该 Signal 视为无效 / 过期 / 重复请求：不推进 playbook，不重复恢复
4. 运行时校验通过后，session workflow 写入 session event：
   - 人工恢复 → `HUMAN_RESUME_RECEIVED`
   - 外部回调 → `EXTERNAL_CALLBACK_RECEIVED`
   - event payload 含 `runId` 和传入数据
5. session workflow 将 Signal 转发至对应 playbook child workflow
6. playbook workflow 的 `Workflow.await()` 被唤醒；playbook 更新 `playbook_run.status = RUNNING`、清空 `waitingReason`，并写入 `PLAYBOOK_RESUMED`
7. playbook 继续按图遍历后续节点；若到达终态则走 §9.3 step 8 终态处理

### 9.6 Session 空闲结束与后续新 session

1. 最后一次交互完成后，session workflow 重置 idle timer
2. idle timer 到期，当前 session 与其 workflow 一并正常结束
3. 后续用户再次发消息时，API 不恢复旧 session，而是创建新的 session
4. API 为该新 session 创建新的 workflow
5. 新消息作为新 session 的首条消息进入新 workflow
6. 新 session 的 `currentOwnerAgentId = primaryAgentId`
7. 第一阶段不自动回灌上一个已结束 session 的对话历史或 `sharedState`
8. 新 session 正常处理消息

## 10. 第一阶段约束

- Owner 切换为扁平模型，无自动回退；单个 agent turn 内的连续切换次数上限由 `maxOwnerSwitchesPerTurn` 控制（默认 `3`）
- Agent turn 执行期间拒绝新消息
- Playbook 活跃期间不允许 `SWITCH_OWNER`，也不允许再次启动 `RUN_PLAYBOOK`
- Playbook 终态（成功/失败/取消）统一以 `PLAYBOOK_COMPLETED` 事件并入 session 状态；非 handoff 状态下触发 owner reevaluation，handoff 状态下直接暴露给人工侧处理
- 运行时校验失败统一走 §5.2 的降级路径：写 `AGENT_DECISION_REJECTED` + 系统生成 `OWNER_REPLY`，不再回调 agent-runtime（`SESSION_HUMAN_HANDOFF` 幂等命中走幂等路径，不算失败）
- Agent-runtime activity 耗尽重试后失败：写 `AGENT_TURN_FAILED` + 系统生成降级 `OWNER_REPLY`，丢弃本次 turn 未提交产物，不自动重试本次 turn（详见 §3.2）
- 不开放主动取消 playbook 的 API 入口；`CANCELLED` 仅由系统内部机制触发
- `sharedState` 为扁平 KV，全量可读可写
- 不使用 `Continue-As-New`，采用 idle 超时结束当前 session + 后续创建新 session / 新 workflow；同时通过 `maxWorkflowAge` / `maxWorkflowHistoryEvents` 为单条 workflow 提供防御性上界
