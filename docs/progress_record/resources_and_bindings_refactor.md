# 资源语义收口与版本锚定改造计划

## Summary
目标是把当前“资源类型本身清楚，但引用方式、版本锚定和运行时生效规则不够清楚”的问题一次收口，重点解决 5 件事：

- 明确资源关系语义：默认引用、覆盖引用、工具启用、版本固定分别是什么
- 限定各资源类型的推荐消费方式，去掉“所有资源都可以同样绑定”的心智负担
- 让发布快照和 runtime 只消费“已解析好的版本 ID”，不再靠 `resourceId` 模糊匹配
- 让资源中心的引用分析和删除阻断原因完全一致
- 清掉当前被 UI 暴露但运行时实际上不生效的重复参数

默认采用“全面收口”方案，并保持控制面编辑体验简单：配置态用资源头 `resourceId` 表达“选哪个资源”，发布态再统一冻结为具体 `resourceVersionId`。

## Key Changes
### 1. 语义模型收口
- 定义 4 种关系并在文档、DTO 命名、UI 文案中统一：
  - 默认引用：助手级默认模型 / Prompt / 知识库
  - 覆盖引用：智能体级模型 / Prompt / 知识库覆盖
  - 工具启用：智能体声明可用 `SKILL` / `MCP`
  - 版本固定：仅对已启用工具固定具体版本
- 明确资源类型定位：
  - `KNOWLEDGE_BASE`：只作为默认/覆盖知识源，不再提供独立手工版本绑定入口
  - `LLM_MODEL`：只作为模型配置资源，不再在助手页重复维护 `temperature/maxTokens`
  - `PROMPT_TEMPLATE`：只作为默认/覆盖 Prompt 资源，不再提供独立手工版本绑定入口
  - `SKILL` / `MCP`：只作为工具资源，必须先启用，再固定版本
- 控制面不再允许“任意资源都能进入通用工具版本固定”；通用“工具版本固定”只保留给工具资源
- 资源归属规则收紧：
  - `ownerType=DOMAIN` 时 `ownerId` 必须等于 `domainId`
  - `ownerType=ASSISTANT` 时该助手必须属于 `domainId` 对应业务域
  - 前端 owner 选项按选定业务域过滤

### 2. 接口与快照改造
- `Catalog` 草稿态 DTO / 前端 types 调整：
  - `AssistantModelPolicy` 删除 `temperature/maxTokens`
  - `RagPolicy` 删除 `topK`
  - 智能体的通用“工具版本固定”结构只允许 `SKILL|MCP`
- `ResourceUsage` / 资源中心接口改为返回结构化引用明细，至少包含：
  - `referenceKind`
  - `sourceType`
  - `sourceId`
  - `sourceName`
  - `resourceId`
  - `resourceVersionId`（仅发布快照/工具固定场景必填）
  - `blocksDeletion`
- 删除阻断逻辑与资源中心共用同一套引用分析结果，避免“页面看起来没用，删除却失败”
- 发布快照契约改造为“显式版本解析”：
  - 助手策略快照保存默认模型 / Prompt / 知识库的 `resourceVersionId`
  - 智能体策略快照保存覆盖模型 / Prompt / 知识库的 `resourceVersionId`
  - 工具快照保存启用工具的 `resourceVersionId[]`
- 发布时校验规则固定为：
  - 非工具资源在发布时自动冻结“当前生效版本”
  - 工具资源必须同时满足“已启用 + 已固定版本”，否则发布失败
  - 同一智能体内如果工具启用了但未固定版本，返回明确错误
  - 旧的“对 KB/LLM/Prompt 做手工版本绑定”不再支持；如历史数据存在，发布时迁移为“忽略手工绑定，仅冻结当前生效版本”

### 3. Runtime 与控制台行为
- `packages/contracts-jvm` 与 `apps/agent-runtime` 同步切换到按 `resourceVersionId` 解析资源
- runtime 删除基于 `resourceId` 的“找第一条”策略；所有执行路径都从快照里的显式 version slot 取资源
- `LLM_MODEL` 的运行参数只读资源配置；助手页不再展示重复的 `temperature/maxTokens`
- `KNOWLEDGE_BASE` 的 `topK` 只读知识库资源配置；助手页不再展示重复的 `topK`
- `AssistantPage` 只保留默认模型 / Prompt / 知识库选择
- `AgentPage` 拆成三块：
  - 默认继承与覆盖
  - 工具启用
  - 工具版本固定
- `ResourceLibraryPage` 改成“引用分析”视图，不只展示显式工具版本固定，要能看到：
  - 助手默认引用
  - 智能体覆盖引用
  - 工具启用
  - 工具版本固定
  - 发布快照冻结引用
- `ResourceCreatePage` 和架构文档统一成 5 类资源蓝图，不再保留“三类蓝图”的旧表述
- 旧 JSON catalog 数据提供一次兼容读取：
  - 旧工具版本固定中的 `SKILL/MCP` 转成新结构
  - 旧工具版本固定中的 `KB/LLM/PROMPT` 丢弃为 legacy 字段并记录 warning，不参与新发布流程

## Test Plan
- Catalog 服务单测：
  - 资源归属校验
  - 资源中心引用分析覆盖默认引用、覆盖引用、工具启用、工具固定、发布冻结
  - 删除阻断原因与引用分析结果一致
  - 发布时工具未固定版本会失败
  - 发布时 KB/LLM/Prompt 自动冻结生效版本
- Runtime 单测：
  - 模型 / Prompt / KB 通过显式 `resourceVersionId` 正确解析
  - 同一 `resourceId` 存在多个版本快照时不会误取第一条
  - 工具调用只使用固定版本资源
- Web 交互测试：
  - 助手页不再出现无效的模型参数与 RAG TopK
  - 智能体页只能给工具做版本固定
  - 资源中心可视化展示全部引用类型
  - 资源创建时 assistant owner 只显示同域助手
- 回归场景：
  - 现有 seed 的 FAQ / 售后 Skill / 人工 MCP 闭环保持可运行
  - 旧本地 catalog 数据能被兼容加载并重新发布

## Assumptions
- 本次改造允许调整 monorepo 内部 API / contract，不要求对外长期兼容旧 DTO
- 当前平台以控制台为主，不保留“非工具资源手工绑定特定历史版本”的产品能力
- `LLM_MODEL` 拥有模型调用参数，`KNOWLEDGE_BASE` 拥有检索参数；助手/智能体只负责选择资源，不重复定义这些参数
- `PROMPT_TEMPLATE.templateType/responseFormat` 继续保留为编辑与说明性字段，本次不把它们升级为强执行语义
