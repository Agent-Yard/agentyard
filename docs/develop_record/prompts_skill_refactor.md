# Prompt 收敛与 Skill 会话级按需加载改造计划

## Summary
继续按收敛方向推进：去掉独立 `Prompt Template` 资源，引入轻量 `Skill` 资源；智能体只配置自己的 `systemPrompt`，首轮 `user prompt` 由系统统一内置 `default_v1` 模式生成。  
`SkillConfig` 固定为 `skillName / skillDesc / skillPrompt`。首轮不注入 `skillPrompt`，只注入 skill 清单摘要；当模型返回 `skillReads` 时，runtime 加载对应 `skillPrompt`。  
本次把 skill 加载语义改为“整个会话期间生效”，并允许单轮同时发生 `skillReads + toolRequests`。

## Key Changes
### 1. 对象模型与公开接口
- `ResourceType` 从 `PROMPT_TEMPLATE` 收敛为 `SKILL`，最终为 `TOOL / KNOWLEDGE_BASE / LLM_MODEL / SKILL`。
- 新增 `SkillConfig`，字段严格定义为：
  - `skillName: string`
  - `skillDesc: string`
  - `skillPrompt: string`
- 删除 `PromptTemplateConfig`、`templateType`、`responseFormat` 及其相关 DTO、OpenAPI、前端类型。
- `AssistantModelPolicy` 删除 `promptTemplateResourceId`，仅保留默认模型。
- `AgentExecutionPolicy` 删除：
  - `promptTemplateResourceId`
  - `inlinePrompt`
- `AgentExecutionPolicy` 新增：
  - `systemPrompt: string`
  - `skillResourceIds: string[]`
- `Agent.instructions` 保留为人类可读职责说明，不参与 runtime prompt。
- `SessionContext` 新增：
  - `loadedSkillResourceVersionIds: string[]`
- `ConversationSessionDto` / 前端 `ConversationSession` 同步新增：
  - `loadedSkillResourceVersionIds: string[]`
- `WorkflowResult` 新增：
  - `loadedSkillResourceVersionIds: string[]`
  使 API 能把本轮新加载 skill 合并回 session 级状态。

### 2. Runtime prompt 组装与 session 级 skill 状态
- runtime 改为统一两段式：
  - `system` = 平台内置系统规则 + `agent.systemPrompt`
  - `user` = 平台内置 `default_v1` 渲染结果
- `default_v1` 首轮统一按需包含：
  - 用户消息
  - 会话记忆
  - 当前 agent 挂载技能摘要列表（只含 `skillName / skillDesc`）
  - 当前 session 已加载且当前 agent 挂载的技能详情（注入 `skillPrompt`）
  - 知识召回结果
  - 工具结果
  - 人工输入
  - 当前节点的路由/工具/输出约束
- `skillPrompt` 的加载状态保存到 session，而不是只保存在当前 agent loop。
- 每次新消息启动 workflow 时，`SessionContext.loadedSkillResourceVersionIds` 会被带入 runtime；因此同一 session 后续轮次和后续 workflow 都能继续使用已加载 skill。
- 已加载 skill 只在“当前 agent 挂载了该 skill”时才会注入 prompt；不会把 session 中所有已加载 skill 无差别塞给所有 agent。

### 3. Agent 节点协议与执行顺序
- 在现有结构化 agent 响应中新增：
  - `skillReads: string[]`
- `skillReads` 的元素使用 `skillResourceVersionId`。
- 首轮模型看到：
  - `availableSkills`（当前 agent 的技能目录，含 `skillName / skillDesc`）
  - `loadedSkills`（当前 session 已加载且当前 agent 可用的技能详情）
- 当模型返回 `skillReads` 时，runtime：
  - 校验 skill 必须属于当前 agent 已挂载 skills
  - 将新增 skill 合并进 session 级 `loadedSkillResourceVersionIds`
  - 忽略重复读取，不重复注入相同 skillPrompt
- 单轮允许同时返回 `skillReads` 与 `toolRequests`。
- 单轮执行顺序固定为：
  1. 处理并合并 `skillReads`
  2. 执行 `toolRequests`
  3. 进入下一轮 prompt
- 若本轮既加载了新 skill 又执行了 tool，下一轮 prompt 同时看到“新加载 skill 详情 + 最新 tool 结果”。
- 若请求未挂载的 skill：
  - 不执行加载
  - 记录观测信息
  - 继续按已有状态推进，不中断整个节点

### 4. 控制面、前端与种子数据
- 资源中心移除 Prompt Template 入口，新增 Skill 资源入口。
- Skill 编辑表单仅包含 `skillName / skillDesc / skillPrompt`。
- 助手页移除默认 Prompt 配置。
- 智能体页改为配置：
  - `systemPrompt`
  - `skillResourceIds`
  - 原有模型、知识库、工具、记忆窗口
- runtime session 详情和会话页数据结构带出 `loadedSkillResourceVersionIds`，用于后续调试与观测。
- seed/mock 中将现有四类 prompt 迁移为四类 skill：
  - 路由 skill
  - FAQ skill
  - 售后策略 skill
  - 人工协同 skill
- 引用分析、发布冻结、删除阻断全部从 prompt resource 切换到 skill resource。

## Test Plan
- Catalog/API
  - 可创建、编辑、发布 `SKILL` 资源
  - `SkillConfig` 仅含 `skillName / skillDesc / skillPrompt`
  - assistant/agent 合同中不再出现 prompt resource 字段
- Runtime
  - 首轮 prompt 只出现 skill 摘要，不出现未加载的 `skillPrompt`
  - 当 session 已加载某 skill 后，后续消息启动的新 workflow 仍能看到该 skillPrompt
  - 当前 agent 未挂载的已加载 skill 不会被注入 prompt
  - 同一 skill 重复请求不会重复注入多份内容
  - 单轮同时 `skillReads + toolRequests` 时，两者都生效，且下一轮能同时看到 skill 详情与 tool 结果
  - 请求未挂载 skill 时被忽略并记录观测信息
  - `system prompt` 仅来自平台规则 + `agent.systemPrompt`
- 集成场景
  - FAQ agent 首轮不读 skillPrompt 也能直接回复
  - 售后 agent 首轮读取 skill 并在同轮调用 tool，下一轮完成决策
  - 人工协同 agent 在 session 已加载 handoff skill 后，下一条消息可直接复用该 skillPrompt
- 前端
  - 资源中心不能再创建 Prompt Template
  - agent 配置页能编辑 `systemPrompt` 和挂载 skills
  - assistant 配置页不再显示默认 Prompt
  - session 数据结构包含已加载 skill 列表

## Assumptions
- 本次为 breaking change，不保留 `PROMPT_TEMPLATE` 兼容层。
- `Skill` 是轻量行为模式资源，不是图节点，不持有独立记忆，不做全局路由，不独立执行。
- v1 只提供一个内置 user prompt 模式 `default_v1`；后续如需多模式，再在代码内增加内置枚举，不开放自定义 user template。
- `skillName / skillDesc` 是 runtime 面向模型的技能目录字段；资源自身的 `name / summary` 继续作为控制面治理元数据。
- `skillPrompt` 只补充行为说明，不承担输出格式定义；输出格式始终由 agent 节点结构化协议和 tool 契约决定。
- session 级 skill 加载状态按 `skillResourceVersionId` 持久化；同一 session 中后续 workflow 可复用，但只有挂载该 skill 的 agent 会实际注入该内容。
