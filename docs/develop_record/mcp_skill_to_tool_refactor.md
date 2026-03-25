# Skill / MCP 全局重构为 Tool + Provider

## Summary
- 将 `Skill` 与 `MCP` 两类一级资源彻底收口为单一 `TOOL` 资源类型，平台对外只表达“可被 agent 使用的工具能力”，不再把接入协议暴露成一级产品概念。
- `MCP` 从资源类型降为 `Tool Provider` 的一种实现方式；另一种首批落地 provider 为 `HTTP`。模型、知识库、Prompt 继续保持独立资源类型。
- 同步重构控制面、发布快照、runtime、运行观测、前端 IA 和 seed/demo，不保留历史兼容，也不保留旧 `tool version pin` 机制。
- agent 只绑定 Tool 资源头；所有资源一律在发布时冻结当前生效版本。runtime 改为通用 Tool 执行，不再按 `agent.role -> skill/mcp` 硬编码。

## Key Changes
### 1. 资源模型与治理语义
- `ResourceType` 从 `SKILL | MCP | KNOWLEDGE_BASE | LLM_MODEL | PROMPT_TEMPLATE` 改为 `TOOL | KNOWLEDGE_BASE | LLM_MODEL | PROMPT_TEMPLATE`。
- `ResourceVersionConfiguration` 删除 `skill`、`mcp`，新增统一的 `tool` 配置。
- 新增统一 `ToolConfig`：
  - `operations`: 工具暴露的业务操作列表，每个操作包含 `name`、`description`、`inputSchema`、`outputSchema`
  - `provider`: 工具实现方式，首批支持 `HTTP` 和 `MCP`
  - `executionPolicy`: `timeoutSeconds`、`retryPolicy`、`authType`
- `ToolProviderConfig` 首批拆为：
  - `HttpToolProviderConfig`: `endpoint`、`method`
  - `McpToolProviderConfig`: `serverName`、`transport`、`connectionUri`、`namespace`、`heartbeatSeconds`、`operationMappings`
- `operationMappings` 负责把 Tool 的业务操作映射到 MCP server 的具体 tool name；不再让 agent 直接感知 `exposedTools`
- 资源蓝图从 5 类收口为 4 类：`知识库 / Tool / LLM 模型 / Prompt 模板`
- 删除“Skill 适合业务动作、MCP 适合集成系统”的文案，统一改成“Tool 是 agent 可调用能力，provider 决定其实现与接入方式”
- 资源中心、引用分析、删除阻断、标签、摘要、seed 命名统一使用 `Tool`

### 2. Assistant / Agent 配置与发布快照
- `AgentExecutionPolicy` 保留 `toolResourceIds`，删除与 `tool version pin` 相关的独立配置心智；agent 草稿态只声明“可用 Tool”
- 删除 `ToolVersionPinDto`、`/agents/{agentId}/tool-version-pins`、`PinToolVersionRequest` 及对应前端交互
- 发布逻辑统一为：
  - 助手和 agent 草稿态都只引用资源头 `resourceId`
  - 发布时对 `LLM / Prompt / KB / Tool` 全部冻结当前 `effectiveVersion`
  - release snapshot 内仍保存显式 `resourceVersionId`，runtime 只消费快照
- `AssistantReleaseResourceDto`、`AssistantRunSnapshot` 中的 Tool 快照统一携带 `tool` 配置与 provider 信息
- 资源引用分析中的 `referenceKind` 去掉 `SKILL/MCP` 语义，统一为 `AGENT_TOOL_ENABLED`、`RELEASE_FROZEN` 等泛化关系
- seed/demo 改为：
  - `退款策略 Tool`，provider=`HTTP`，operation=`evaluate_refund`
  - `工单协同 Tool`，provider=`MCP`，operations 至少含 `create_ticket`、`append_comment`

### 3. Runtime 执行内核
- Python runtime 的资源快照模型删除 `SkillConfig`、`McpConfig`，改为统一 `ToolConfig` + `ToolProviderConfig`
- 资源解析函数统一为 `resolve_tool_resources`，不再筛 `configuration.skill` / `configuration.mcp`
- agent 节点协议改为通用结构化输出，至少包含：
  - `message`
  - `routeDecision`
  - `toolRequests`
  - `finish`
  - `humanRequest`
- `toolRequests` 使用统一格式：`toolResourceVersionId`、`operation`、`arguments`
- runtime 为每个 agent 节点提供“可用 Tool 清单 + operation 契约摘要”，由模型按结构化协议选择是否调用工具
- runtime 执行改为通用 Tool loop：
  - 单个 agent 节点最多 3 轮 `LLM -> Tool -> LLM`
  - 当模型输出 `toolRequests` 时，runtime 顺序执行并写入 `tool_results`
  - 当模型输出 `routeDecision` / `finish` 时，进入下一跳或结束
- `HTTP` provider 使用现有 HTTP adapter 思路统一执行
- `MCP` provider 使用现有 MCP adapter 思路统一执行，但走 operation mapping，不再在业务代码中写死 `create_ticket`
- 删除 `policy` 节点只能调用 Skill、`handoff` 节点只能调用 MCP 的角色硬编码；`agent.role` 只保留展示与 prompt 语义，不再承载执行分支逻辑
- 路由、人工节点、checkpoint 机制保留；tool 失败统一落入通用工具失败语义，可按 agent 输出决定重试、转人工或终止

### 4. 运行观测与公共契约
- `ToolInvocationSnapshot` 继续保留为单次调用记录，但 `toolType` 改为 `providerType`
- 删除 `McpInvocationSummary`，统一替换为 `ToolOutcomeSummary`
- `ToolOutcomeSummary` 至少包含：
  - `toolResourceId`
  - `toolResourceName`
  - `operation`
  - `providerType`
  - `status`
  - `externalReference`
  - `recommendedAction`
  - `detail`
- `WorkflowResult`、`WorkflowInstance`、`ConversationSession` 中的 `mcpSummary/latestMcpSummary` 改为 `latestToolOutcome`
- Web 运行页和流程页删除“MCP 工单/MCP 结果”专有展示，统一改成“工具结果摘要/外部协同摘要”
- API、OpenAPI、`packages/contracts`、`packages/contracts-jvm`、前端 types 全量改成新 wire shape，不保留旧字段别名

### 5. 控制台 IA 与交互
- `资源新建` 页只展示一个 `Tool` 蓝图；进入配置编辑后再选择 provider=`HTTP | MCP`
- `ResourceVersionConfigEditor` 与 summary 组件统一成 Tool 视图：
  - 上半区配置通用 operation 与执行策略
  - 下半区按 provider 展开 HTTP 或 MCP 专属字段
- `AgentPage` 去掉“工具版本固定”整块，只保留“可用 Tool 集”
- Agent 表单可展示 Tool 的 operation 摘要，帮助开发者理解 agent 能调用什么
- `ResourceLibraryPage`、`mock.ts`、`api.ts`、运行态页面、文档示例全部统一改词为 Tool
- README、架构文档、进展文档同步更新为 `Tool + Provider` 术语，不再出现“Skill/MCP 并列一级资源”的表述

## Public APIs / Interfaces
- `ResourceType`:
  - 删除 `SKILL`、`MCP`
  - 新增/保留 `TOOL`
- `ResourceVersionConfiguration`:
  - 删除 `skill`、`mcp`
  - 新增 `tool`
- 新增类型：
  - `ToolConfig`
  - `ToolOperationConfig`
  - `ToolProviderType`
  - `ToolProviderConfig`
  - `HttpToolProviderConfig`
  - `McpToolProviderConfig`
  - `ToolOutcomeSummary`
- 删除类型：
  - `SkillConfig`
  - `McpConfig`
  - `ToolVersionPinDto`
  - `PinToolVersionRequest`
  - `McpInvocationSummary`
- 删除接口：
  - `PUT /agents/{agentId}/tool-version-pins`
  - `POST /tool-version-pins` 若仍存在一并移除
- `WorkflowResult` / `WorkflowInstance` / `ConversationSession`:
  - `mcpSummary` / `latestMcpSummary` 改为 `latestToolOutcome`
- runtime start/resume 契约中的资源快照改为统一 Tool 结构

## Test Plan
- Catalog 单测：
  - `TOOL` 资源创建、更新、发布、删除阻断
  - Tool provider=`HTTP/MCP` 的配置校验
  - Agent 绑定 Tool 后，发布时自动冻结 Tool 的 `effectiveVersion`
  - 资源中心引用分析覆盖 `AGENT_TOOL_ENABLED` 与 `RELEASE_FROZEN`
  - 删除旧 `tool version pin` 逻辑后的发布校验与错误提示
- Runtime 单测：
  - agent 节点可基于统一 Tool 清单发起结构化 `toolRequests`
  - HTTP Tool 调用成功/失败
  - MCP Tool 调用成功/失败
  - operation mapping 正确把业务 operation 转到 MCP server tool
  - Tool 调用结果被回填到后续 prompt 与 route decision
  - 多轮 tool loop 在 3 轮上限内正确收口
  - Tool 失败转人工或终止的通用路径
- Workflow / API 集成测试：
  - FAQ 场景无 Tool 直接完成
  - 售后场景通过 HTTP Tool 返回策略并继续路由
  - 人工协同场景通过 MCP Tool 创建外部工单并进入 HUMAN 节点
  - 恢复后继续执行并在 workflow/session 中暴露 `latestToolOutcome`
- Web 测试：
  - Tool 蓝图创建与 provider 切换
  - Agent 页仅选择 Tool，不再配置版本 pin
  - 资源详情正确展示 operation 与 provider 配置
  - 运行页/流程页展示泛化后的工具摘要
- Seed / smoke：
  - 现有 demo 闭环在新模型下保持“FAQ 自动完成 + 售后 Tool + 人工协同 Tool”可运行

## Assumptions
- 本次为彻底 breaking change，不做旧 DTO、旧 JSON catalog、旧前端 mock、旧 runtime payload 的兼容读取
- 首批真正可执行的 Tool provider 只有 `HTTP` 与 `MCP`；`Workflow Activity`、`Function Call` 不进入本轮 public enum 与 UI
- Tool 统一采用“草稿绑定资源头、发布冻结生效版本”的治理方式，不再保留独立手工 pin 历史版本能力
- Tool 是 agent 唯一可见的外部能力对象；provider、transport、namespace、endpoint 都属于 Tool 内部实现细节
- agent 节点的通用执行采用结构化输出驱动，不依赖 provider-specific 分支逻辑
