## 统一消息 Payload 改造落地

### Summary
把运行态消息从“对外/对内都以 `content` 文本为核心”改成“双层模型”：

- 对外公共消息契约统一为 `payloadType + payload`
- 对内存储与运行时上下文额外保留 `content`，作为 LLM 上下文、列表摘要和检索/记忆语义

本次改造覆盖 contracts、OpenAPI、API Runtime、数据库、worker/agent-runtime 的 `SessionContext`、以及 Web 发送与渲染链路。v1 消息类型固定为 `TEXT` 和 `EXTERNAL_INTERACTION`；`TEXT` 也必须走 payload，不再走特殊文本字段。

### Key Changes
- 公共契约统一：
  在 [packages/contracts/src/index.ts](/Users/eric/projects/agentyard/packages/contracts/src/index.ts) 和 OpenAPI 中新增消息 payload 类型体系：
  `ConversationPayloadType = TEXT | EXTERNAL_INTERACTION`；
  `TextMessagePayload` 至少包含 `text`；
  `ExternalInteractionMessagePayload` 对齐现有设计文中的卡片语义。
- 对外 API 统一入站/出站：
  `ConversationMessage` 响应改为暴露 `payloadType + payload`，不再暴露 `content`；
  `ConversationMessageRequest` 改为 `customerId + payloadType + payload`；
  `CreateConversationSessionRequest` 的 `openingMessage` 改为同样的 payload 结构，而不是纯字符串。
- 内部 DTO/存储双轨：
  `ConversationMessageDto` 和 `conversation_message` 表改为同时保存 `payload_type`、`payload_json`、`content`；
  `content` 是内部字段，不对外暴露；
  `TEXT` 消息的 `content` 默认等于 `payload.text`；
  `EXTERNAL_INTERACTION` 消息的 `content` 由服务端生成摘要/说明文本，不要求能从 payload 机械推导。
- RuntimeService 收口规则：
  所有入站消息先规范化为内部消息对象，再统一持久化和组装 workflow 请求；
  placeholder assistant message、final reply、failure/system message 都以 `TEXT` payload 生成；
  为未来 `EXTERNAL_INTERACTION` 保留显式 builder，不允许直接塞 JSON 文本。
- 运行时上下文同步升级：
  [packages/contracts-jvm/src/main/java/com/agentyard/contracts/runtime/WorkflowContracts.java](/Users/eric/projects/agentyard/packages/contracts-jvm/src/main/java/com/agentyard/contracts/runtime/WorkflowContracts.java) 中的 `SessionMessageSnapshot` 改为携带 `payloadType + payload + content`；
  `SessionContext.latestMessage` 从纯字符串改为同样的消息快照；
  `WorkflowStartRequest.question` 继续保留为规范化文本，用于检索与 prompt 主问题，不把“消息 refactor”扩散成所有 workflow 输入都多态化。
- worker / agent-runtime 对齐：
  worker 仅透传新结构；
  agent-runtime 的 Pydantic `SessionMessageSnapshot` / `SessionContext` 同步升级为双轨结构；
  记忆窗口、prompt 历史构建仍读取 `content`，但 payload 保留在上下文中供后续 structured handling。
- Web 端统一 payload：
  运行态输入框创建/发送时直接构造 `TEXT` payload；
  运行态消息渲染从 `message.payloadType` 分发：
  `TEXT` 渲染 `payload.text`；
  `EXTERNAL_INTERACTION` 预留卡片渲染组件，至少能显示 title/description/status/主按钮区域；
  页面不再直接读 `message.content`。
- 数据库迁移：
  新增 V2 migration，修改 `conversation_message` 表增加 `payload_type` 和 `payload_json`，保留 `content`；
  不为旧数据兼容做额外设计，但迁移脚本应让本地空库和现有测试库都能直接启动。

### Public Interfaces
- `ConversationMessage`
  删除对外 `content`
  新增 `payloadType`
  新增 `payload`
- `ConversationMessageRequest`
  从 `{ customerId, message }` 改为 `{ customerId, payloadType, payload }`
- `CreateConversationSessionRequest`
  从 `{ ..., openingMessage: string }` 改为 `{ ..., openingMessage: { payloadType, payload } | null }`
- `SessionMessageSnapshot`
  新增 `payloadType`
  新增 `payload`
  保留内部 `content`
- `SessionContext.latestMessage`
  从字符串改为消息快照
- v1 payload schema
  `TEXT`: `{ text: string }`
  `EXTERNAL_INTERACTION`: 复用现有设计文建议字段，至少包含 `interactionTaskId / interactionType / title / description / status / primaryAction / secondaryActions / displayHints`

### Test Plan
- Java contracts / API：
  更新 RuntimeController、RuntimeService、InMemory/JDBC repository、OpenAPI 对应测试；
  覆盖创建会话、发送消息、workflow 启动失败、workflow 收口后消息更新；
  断言内部 DTO/仓储保存了 `payloadType/payload/content`，对外 API 只返回 payload。
- JDBC / migration：
  增加 repository 读写测试，验证 `conversation_message` 的 `payload_type` 和 `payload_json` 正确 round-trip；
  启动测试验证 V2 migration 后 schema 可用。
- worker / gateway：
  更新 `AgentRuntimeGatewayTest`，验证 `SessionContext` 和 `SessionMessageSnapshot` 的新 JSON 形态可序列化。
- agent-runtime：
  更新 `test_memory_prompt.py`，验证历史消息改为双轨后：
  `build_conversation_history` 继续读取 `content`；
  `latestMessage` 接受完整消息对象；
  启动/恢复请求都能解析新的 session context。
- Web：
  更新 `api.test.ts` 与运行态页面相关测试/静态检查，确认输入构造 `TEXT` payload、消息列表基于 `payloadType` 渲染，且不再依赖 `message.content`。

### Assumptions
- 本次改造只针对“会话消息”链路，不改 `TaskLaunchRequest.question` 的文本模型。
- 对外 API 不暴露 `content`；`content` 是内部持久化和运行时上下文字段。
- `TEXT` 消息的 canonical 语义在 payload；`content` 只是其内部文本投影。
- `EXTERNAL_INTERACTION` 虽然业务未全量实现，但本次要把 contracts、存储、上下文和前端渲染分发位一次铺好。
