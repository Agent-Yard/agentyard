# 知识库改造成 LLM 自主调用的系统内置工具

## 摘要
- 这次做一次性语义切换，不保留“关联即预检索”的旧行为。
- 关联知识库改为“为 agent 提供一组系统内置知识工具”的能力开关；是否检索、检索几次、何时读取详细内容，全部由 LLM 在既有 `TOOL_CALL` 闭环里自主决定。
- 同步做全局命名清理：`rag*` 统一改成知识访问语义，避免继续把“预检索”旧含义带进后续设计。
- v1 采用两段式内置能力：`knowledge_search` + `knowledge_read`。不把知识库做成用户可配置 Tool 资源，而是由 runtime 按生效知识绑定动态注入。

## 关键变更
### 1. 配置、DTO、合同统一重命名
- `RagPolicy` / `ragPolicy` 重命名为 `KnowledgeAccessPolicy` / `knowledgeAccessPolicy`。
- `ragEnabled` 重命名为 `knowledgeEnabled`。
- `assistantKnowledge` 重命名为 `assistantKnowledgeBinding`。
- agent 运行时快照里的 `knowledge` 重命名为 `knowledgeBinding`。
- Web 表单、OpenAPI、TS contracts、JVM contracts、API DTO、runtime snapshot 全部同步改名，不做兼容层。
- UI 文案里的 “RAG” 全部替换成“知识访问”或“知识能力”。

### 2. 运行时工具模型改成统一的“可调用能力”
- runtime 内部新增统一工具定义模型，区分 `RESOURCE` 和 `BUILTIN` 两类来源。
- `availableTools` 从“资源级 + operation 字段”改为“操作级扁平列表”；每个可调用能力都有稳定 `toolId`。
- 资源工具按 operation 扁平化，`toolId` 采用 `resource:<resourceVersionId>:<operationName>`。
- 内置知识工具固定为：
  - `builtin:knowledge_search`
  - `builtin:knowledge_read`
- `ToolRequest` 合同改为 `{ toolId, arguments }`，不再使用 `{ toolResourceVersionId, operation, arguments }`。
- `ToolInvocationSnapshot` 和 `ToolOutcomeSummary` 改为工具中心模型，至少包含：
  - `toolId`
  - `toolName`
  - `toolKind` (`RESOURCE | BUILTIN`)
  - `providerType`
  - `resourceId` / `resourceName`（内置工具为 `null`）
  - `status` / `detail` / `result`
- 内置知识工具必须像普通工具一样进入 `toolCalls`、`tool_history`、`latestToolOutcome`，这样 workflow 投影和前端可见的调用轨迹保持统一。

### 3. agent-runtime 去掉强制预检索，改为按绑定注入内置知识工具
- 删除节点启动时的 eager retrieval；不再在进入 agent 节点后直接调用知识服务。
- 删除提示词中的“知识召回结果”预注入块；知识信息只能通过工具结果进入上下文。
- 保留“生效知识绑定”的解析逻辑，但它只用于：
  - 判断当前 node 是否应注入内置知识工具
  - 给内置知识工具提供固定的 `snapshotId/defaultTopK/minScore/retrievalMode`
- 注入规则：
  - 当前 node 存在有效 `knowledgeBinding` 时，注入 `knowledge_search` 和 `knowledge_read`
  - 没有有效绑定时，不注入任何知识工具
- 配置校验改为前置失败，不允许“开了知识能力但运行时实际没有绑定”的灰色状态：
  - assistant `knowledgeAccessPolicy.enabled=true` 时必须有 `knowledgeBaseId`
  - agent `knowledgeEnabled=true && inheritAssistantKnowledge=false` 时必须有 `knowledgeBaseId`
  - agent `knowledgeEnabled=true && inheritAssistantKnowledge=true` 时，发布时必须能解析到 assistant 侧知识绑定
- 内置知识工具不是 catalog 资源，不出现在 assistant release 的 `resources` 列表里；它们只由 runtime 按绑定动态生成。

### 4. 两段式知识能力定义
- `knowledge_search`
  - 输入：`query` 必填；`topK`、`minScore`、`retrievalMode` 可选
  - 默认值：未传时使用当前 `knowledgeBinding` 的 `defaultTopK/minScore/retrievalMode`
  - 输出：
    - `knowledgeBaseId`
    - `knowledgeBaseName`
    - `knowledgeReleaseId`
    - `knowledgeReleaseVersion`
    - `lowConfidence`
    - `hits[]`，沿用现有检索命中结构：`chunkId/documentId/documentTitle/sourceUri/snippet/score/pageNumber/headingPath`
  - 执行：继续复用现有知识服务检索接口
- `knowledge_read`
  - 输入：`chunkIds: string[]`
  - 输出：
    - `knowledgeBaseId`
    - `knowledgeBaseName`
    - `knowledgeReleaseId`
    - `knowledgeReleaseVersion`
    - `chunks[]`，每项至少包含：`chunkId/documentId/documentTitle/sourceUri/headingPath/pageNumber/content`
  - 约束：
    - 只允许读取当前绑定 `snapshotId` 内存在的 chunk
    - 去重后按请求顺序返回
    - 不属于当前 snapshot 的 chunk 直接过滤，不跨 snapshot 读取
- prompt 规则补充：
  - 需要知识库事实时优先用 `knowledge_search`
  - 需要展开命中内容时再用 `knowledge_read`
  - 不允许把未调用知识工具时的猜测包装成“知识库已确认”

### 5. 知识服务、API、前端与文档
- 知识服务保留现有检索接口，新增一个内部读取接口，建议固定为 `POST /internal/read-chunks`，请求体包含 `indexSnapshotId` 和 `chunkIds`。
- API/catalog/runtime 中所有知识绑定冻结逻辑保留；变的是“绑定的消费方式”，不是“绑定是否冻结”。
- 前端 assistant/agent 配置页同步改名，但交互不变：
  - assistant 选择默认知识库
  - agent 选择是否启用知识能力、是否继承 assistant 知识、是否覆盖知识库
- 运行态页面的工具调用展示增加 built-in 标识，确保用户能看见知识搜索/读取过程。
- 文档更新只改当前有效文档，不改 `docs/develop_record/`。

## 测试计划
- contracts / OpenAPI / TS 类型序列化测试全部更新，确认重命名后的字段一致。
- catalog / runtime 服务测试覆盖：
  - assistant/agent 知识配置的发布冻结仍然正确
  - 开启知识能力但无法解析有效绑定时发布失败
- agent-runtime 测试覆盖：
  - 有知识绑定时，首轮 prompt 只出现内置知识工具，不再预注入检索结果
  - 无知识绑定时，知识工具不出现
  - `knowledge_search` 可被模型请求并写入 `toolCalls/tool_history`
  - `knowledge_read` 只能读取当前 snapshot 内的 chunk
  - 模型可以完全不调用知识工具直接完成节点
  - 资源工具和内置知识工具可在同一轮次链路中混用
- knowledge-service 测试覆盖：
  - `read-chunks` 只返回 snapshot 内 chunk，顺序稳定，越界 chunk 被过滤
- web 测试覆盖：
  - assistant/agent 配置页使用新字段名
  - workflow/runtime 展示能区分内置知识工具调用
- 回归一条端到端场景：
  - agent 先 `knowledge_search`
  - 再 `knowledge_read`
  - 最终输出回答
  - workflow 投影里能看到两次工具调用且没有启动前自动检索痕迹

## 假设与默认
- 不考虑向后兼容，不做双读双写，不保留旧字段别名。
- 旧数据库中的历史 JSON 快照、workflow checkpoint、前端缓存如果仍使用 `rag*` 字段，升级后视为无效，不做迁移兼容。
- `KnowledgeBindingSnapshot` 结构本身保留，只重命名其宿主字段和消费语义。
- 内置知识工具的 providerType 固定为 `BUILTIN`。
- 本次不引入模型厂商原生 function calling；继续沿用当前 JSON 决策 -> `TOOL_CALL` -> 执行 -> 回填结果的 runtime 主循环。
