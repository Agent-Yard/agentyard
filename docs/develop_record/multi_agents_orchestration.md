# 真正多智能体运行编排升级方案

## Summary
- 目标不是修补当前固定链路，而是把系统升级为“单助手内、可真实执行、可暂停恢复、按图编排”的多智能体运行平台。
- 保留总体分层：`Spring API + Temporal` 负责控制面、运行实例和长流程托管；`Python agent-runtime + LangGraph` 成为真正的编排内核。
- 允许破坏性升级：重构 orchestration 数据模型、运行时契约、seed/demo 数据和前端编排页，不做旧图兼容保留。

## Key Changes
### 1. 控制面与数据模型
- 将 assistant 编排模型从“仅 agent 节点 + 文本边”升级为显式图模型：节点至少支持 `START`、`AGENT`、`HUMAN`、`END`，其中 tool 不做独立图节点，仍由 agent 内部调用。
- 重构 orchestration schema：节点持有稳定 `nodeKey`、`nodeType`、展示信息、关联 `agentId` 或 `humanActionConfig`；边持有稳定 `edgeKey`、`sourceNodeKey`、`targetNodeKey`、结构化谓词与默认路由标记。
- assistant 发布时不只冻结资源版本，还要冻结“可执行图快照 + agent 执行配置 + 资源锚点”，运行时永远基于 release snapshot 启动，避免配置漂移。
- 破坏性重写 demo/seed：提供一套真实可执行的多 agent 图，覆盖 FAQ、售后、人工协同三类节点流转。

### 2. 运行时与 Temporal
- 废弃当前“单次 activity 调 Python 然后直接返回最终结果”的模式，改为 `start/resume` 双阶段运行协议。
- 每次用户消息仍创建一个 workflow instance，但 workflow 成为真正的长流程：启动时调用 runtime `start`；若 runtime 返回 `WAITING_HUMAN`，Temporal workflow 持久化 checkpoint 并等待 signal；收到人工动作后再调用 runtime `resume` 继续执行，直到完成或失败。
- `/workflows/{workflowId}/human-action` 从“直接改内存状态”改为真正 signal workflow，并携带结构化人工输入。
- `/tasks` 简化入口改为必须显式指定 `assistantId`，不再默认取场景第一个助手。
- runtime 运行实例补充图执行信息：当前节点、已走边、agent 输出摘要、tool 调用记录、human checkpoint、恢复次数、最终资源锚点。

### 3. Python 多智能体编排内核
- 基于 LangGraph 重建执行内核，不再写死 `route -> respond -> mcp -> finalize`，而是按发布快照里的图动态构图。
- 统一 agent 节点协议：每个 agent 节点执行后输出结构化结果，至少包含 `message`、`state_patch`、`route_decision`、`tool_calls`、`human_request`、`finish` 标记。
- 路由采用结构化决策，不依赖自然语言 edge condition 解释。运行时根据 agent 输出匹配 `edgeKey` 或显式 `next_node_key` 决定下一跳。
- 建立共享运行态 `ExecutionState`，承载会话上下文、agent 产物、检索结果、tool 结果、人工输入与最终回复。
- 支持真正的 `HUMAN` 节点：运行时生成 checkpoint 与待办说明，恢复时把人工动作写回状态并从该节点后续边继续。
- 加入图校验器：单入口、可达性、禁止悬空边、禁止无出口死节点、`HUMAN/END` 语义约束、agent 引用必须存在。

### 4. 真实资源执行层
- LLM：按已发布模型资源真实调用 provider，agent 级配置优先，缺省回退 assistant 默认模型与 Prompt。
- 知识库：实现轻量检索适配层，按知识库资源配置从可访问文档源加载内容，先提供仓内可运行的 keyword/BM25/hybrid 检索闭环，不绑定外部向量库。
- Tool provider：本阶段统一落地 `HTTP / MCP` 两种 provider；`HTTP` 直接调用 endpoint，`MCP` 通过 operation mapping 接入远端工具。
- 所有资源执行都写入节点执行记录与 workflow 观测数据，失败时可按节点策略决定重试、转人工或终止。

### 5. 前端与观测
- 升级编排页为新图模型编辑器：节点面板支持 `START/AGENT/HUMAN/END`，边编辑支持结构化路由键与默认分支，禁止保存非法图。
- 助手页与智能体页补齐运行时相关配置展示，明确 agent 继承助手默认策略与实际覆盖关系。
- 运行时会话页与流程页展示真实节点轨迹、当前阻塞节点、人工待办、恢复后的继续执行结果，以及 tool/资源调用摘要。
- 前端 contract、OpenAPI、共享 types 全部同步到新 schema，不保留旧 orchestration wire shape。

## Public APIs / Interfaces
- `AssistantOrchestrationDto` 改为显式图 schema，旧 `nodeId/fromNodeId/toNodeId + free-text condition` 结构废弃。
- `WorkflowStartRequest` 不再传三段松散 JSON 字符串，改为传强类型 release snapshot、graph snapshot、session context。
- 新增 runtime `resume` 契约，支持以 checkpoint/run token + human payload 继续执行。
- `WorkflowResult` 扩展为可表达 `RUNNING / WAITING_HUMAN / COMPLETED / FAILED` 的中间态结果，并携带 checkpoint、currentNode、tool summaries、final assistant reply。
- `HumanActionRequest` 升级为结构化输入，不再只有 `action/comment` 两个自由字段。
- `/tasks` 启动请求必须显式带 `assistantId`。

## Test Plan
- 图模型单元测试：合法图校验、非法图拒绝、结构化路由匹配、默认分支回退、死节点检测。
- runtime 单元测试：agent 继承/覆盖模型与 Prompt、生效资源解析、知识检索、HTTP Tool 调用、MCP Tool 调用、tool 失败转人工。
- workflow 集成测试：用户消息启动、多 agent 串行分支、进入 `WAITING_HUMAN`、signal 恢复、恢复后继续执行到完成。
- API 集成测试：assistant 发布冻结图快照与资源锚点、session 绑定 release 启动、workflow 详情返回新观测字段。
- 前端测试：编排页创建/保存新图、非法图阻止保存、流程页展示人工阻塞与恢复结果。
- 端到端 demo：FAQ 自动完成、售后策略调用 Tool、投诉进入人工节点并恢复闭环。

## Assumptions / Defaults
- 仅做单 assistant 内多 agent 编排，不引入跨 assistant workflow。
- 编排内核放在 Python runtime，执行框架继续采用 LangGraph。
- 节点类型采用显式模型，tool 不独立成图节点。
- 路由采用结构化决策，不依赖自然语言解释边条件。
- 本阶段前后端一起升级，且允许 breaking change；旧 orchestration 数据不保留兼容。
- 真实资源执行第一阶段优先保证 `LLM + 轻量知识检索 + HTTP Tool + STREAMABLE_HTTP MCP Tool` 闭环可用。
