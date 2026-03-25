## Agent Runtime 自循环全局重构方案

### Summary
将当前“最多 3 轮 + 若干隐式 fallback”的 agent 自循环，重构为“显式状态机 + 严格结构化输出 + 明确人工挂起”的执行模型，目标是让每一轮的输入、动作、终止条件、失败路径都可预测且可测试。

默认设计选择：
- 自循环改为强状态机
- 非 JSON / 非法结构化输出按严格失败处理，不再做隐式工具猜测或路由猜测
- `humanRequest` 升级为真正的挂起信号，直接产生 checkpoint 和 `WAITING_HUMAN`

### Key Changes
#### 1. 重写 agent turn 协议，去掉含糊控制语义
- 用新的结构化响应模型替代当前 `finish + routeDecision + skillReads + toolRequests + humanRequest` 的松散组合。
- 新模型采用“显式决策”语义，至少包含：
  - `decisionType`: `FINAL` | `TOOL_CALL` | `HUMAN_HANDOFF`
  - `message`
  - `routeDecision`
  - `skillReads`
  - `toolRequests`
  - `humanRequest`
- 校验规则固定化：
  - `FINAL` 时禁止 `toolRequests`
  - `TOOL_CALL` 时必须有合法 `toolRequests`
  - `HUMAN_HANDOFF` 时必须有 `humanRequest`
  - `skillReads` 允许与 `TOOL_CALL` 同轮并存，但只作为补充上下文加载动作
- 删除对自然语言输出的启发式 fallback，不再保留 `choose_tool_request` 和“非结构化输出自动推工具”的路径。
- 删除基于问题关键词的隐式路由推断；路由只来自合法 `routeDecision`、合法工具返回映射，或“唯一默认路由”的确定性回退。

#### 2. 将自循环改为显式阶段状态机
- `execute_agent_node` 改为固定阶段：
  1. `PREPARE_CONTEXT`
  2. `CALL_MODEL`
  3. `VALIDATE_RESPONSE`
  4. `APPLY_SKILL_READS`
  5. `EXECUTE_TOOL_REQUESTS`
  6. `FINALIZE` / `PAUSE_FOR_HUMAN` / `FAIL`
- 循环不再写死成 `for range(3)`；改为配置化 `max_turns`，默认 6 轮。
- 每轮只允许一种终态：
  - 产出最终回复
  - 发起工具调用并进入下一轮
  - 进入人工挂起
  - 以结构化失败结束
- 达到 `max_turns` 仍未完成时，直接进入明确失败或人工兜底，不允许“最后一轮 tool request 被静默丢弃”。
- 每轮记录 `turn_index`、`decision_type`、`loaded_skills_delta`、`tool_calls_delta`、`route_source`、`failure_reason`，便于排障。

#### 3. 重构工具执行状态，去掉当前 `tool_results` 的结构缺陷
- 将 `state["tool_results"]` 从“字符串 key -> 任意 dict”改为有类型的有序记录列表，例如 `tool_history: List[ToolExecutionRecord]`。
- 每条工具记录至少包含：
  - `agentId`
  - `toolResourceVersionId`
  - `toolResourceName`
  - `operation`
  - `arguments`
  - `rawResult`
  - `normalizedOutcome`
  - `status`
  - `createdAt`
- `latest_tool_outcome` 不再和 `tool_results` 重复存储非一致结构，而是从 `tool_history` 读取最后一个成功结果，或在写入时保证同一数据模型。
- `build_tool_payload` 改为从最新工具记录里读取 `ticketId` / `externalReference` / `recommendedAction`，彻底修复多轮工具链上下文断裂问题。
- prompt 中的“工具结果”不再直接塞整个原始 `tool_results` dict，而是输出精简、顺序稳定、面向模型的标准化摘要。

#### 4. 让 `humanRequest` 成为真正的运行时挂起机制
- agent 返回 `HUMAN_HANDOFF` 时，当前 `AGENT` 节点直接进入 `WAITING_HUMAN`，无需依赖图中必须存在 `HUMAN` 节点。
- checkpoint 默认记录“恢复后重新进入当前 agent 节点”，由恢复后的 `human_input` 继续驱动该 agent 完成后续决策。
- 图中的显式 `HUMAN` 节点继续保留，作为流程编排层的人工节点；模型主动请求人工则走运行时挂起，不强绑图结构。
- `ExecutionCheckpoint.statePayload` 与恢复逻辑同步升级，保存：
  - 当前 agent 执行阶段
  - 当前 turn 计数
  - 已加载 skill
  - 工具历史
  - 挂起原因
- `WorkflowResult` 保持对外语义清晰：
  - `WAITING_HUMAN` 仅表示真正挂起
  - 恢复后若流程完成，`escalationRequired` 清零
  - `humanTask` 明确区分“图节点人工任务”与“agent 主动请求人工”

#### 5. 强化图路由与错误策略
- 路由解析改成严格校验：
  - 指定 `routeDecision` 但图中不存在对应边时，直接视为执行错误，不再静默 fallback 到 default
  - 工具返回的 `routeKey` 也必须校验图边合法性
- 只有“未指定 route 且图上存在唯一 default edge”时，才允许默认落边。
- 明确三类失败：
  - `MODEL_OUTPUT_INVALID`
  - `TOOL_REQUEST_INVALID`
  - `ROUTE_INVALID`
- 失败默认行为：
  - 若可转人工，则进入 `WAITING_HUMAN`
  - 否则返回明确错误并终止节点执行
- 日志统一输出 `workflowInstanceId / nodeKey / turnIndex / failureCode`，便于后续接监控。

### Public API / Type Changes
- 调整 agent 结构化输出协议，替换现有 `AgentStructuredResponse` 的控制语义。
- 重构内部 `AgentState`：
  - `tool_results` 改为 `tool_history`
  - 新增 `agent_turn_state` 或等价字段记录状态机阶段与轮次
- `ExecutionCheckpoint.statePayload` 的内部结构变更，不保留旧格式兼容。
- `humanTask` 增加来源标识或等价字段，区分流程图人工节点与 agent 主动人工请求。
- 保留 `/agent-runs/start` 与 `/agent-runs/resume` 端点，但其内部状态载荷与恢复语义按新模型重建。

### Test Plan
- 保留并重写当前 memory / skill / tool 主路径测试，使其断言新的状态机行为。
- 新增以下核心场景：
  - 同一轮 `skillReads + toolRequests`，下一轮能读取新 skill 与工具摘要
  - 多轮工具链能正确继承 `ticketId` / `externalReference`
  - 第 N 轮仍返回工具请求时，不会被静默吞掉，而是继续执行或明确失败
  - 非 JSON 输出直接进入严格失败或人工挂起
  - 非法 `toolResourceVersionId` / operation / routeKey` 被明确拒绝
  - `humanRequest` 会直接生成 `WAITING_HUMAN` 与 checkpoint
  - resume 后重新进入原 agent 节点，并带上 `human_input` 继续执行
  - 图中存在 `HUMAN` 节点的旧流程仍能按图挂起与恢复
  - 没有 default edge 且未提供合法 route 时返回明确错误
  - 工具失败时不会悄悄落到错误分支
- 测试方式以 `unittest` 为主，避免依赖当前环境缺失的 `pytest`。

### Assumptions
- 这次重构不要求兼容旧 checkpoint 载荷、旧结构化输出格式或旧的隐式 fallback 行为。
- 允许调整 prompt 协议与内部状态模型，只要 `/agent-runs/start`、`/agent-runs/resume` 的业务语义保持清晰。
- `humanRequest` 触发挂起时，恢复入口默认是“回到当前 agent 节点继续推理”，而不是强制跳转到图中的某个 `HUMAN` 节点。
- 默认将 `max_turns` 提升为 6；如实现时发现配置层已存在更合适的全局参数，可改为可配置但默认值仍取 6。
