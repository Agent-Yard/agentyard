# 平台事件日志落地方案

## Summary
- 交付一个独立于运行投影的 append-only 审计账本 `platform_event`，覆盖首批控制面关键治理动作，以及运行态 `session event / playbook run` 的统一审计边界。
- 首版查询入口做成全局 `GET /api/events`，直接支持分页；控制台对象详情页补“操作历史”面板。
- 本期不做 event sourcing，不改现有 `session_runtime_*` 投影职责，不把知识导入/重试/快照类操作纳入首批审计。

## Key Changes
### 1. 数据模型与公共契约
- 新增迁移 `V8__platform_event.sql`：
  - 表：`platform_event(id, event_type, aggregate_type, aggregate_id, actor_id, payload, occurred_at)`
  - 索引：
    - `(aggregate_type, aggregate_id, occurred_at desc, id desc)` 用于对象历史查询
    - `(occurred_at desc, id desc)` 用于全局审计查询
- 新增 API/契约类型：
  - `PlatformEventDto { id, eventType, aggregateType, aggregateId, actorId, payload, occurredAt }`
  - `PlatformEventPageDto { items, nextCursor }`
  - `PlatformAggregateType`：`DOMAIN | SCENARIO | ASSISTANT | AGENT | PLAYBOOK | RESOURCE | KNOWLEDGE_BASE | SESSION | PLAYBOOK_RUN`
- 新增查询接口：
  - `GET /api/events?aggregateType=&aggregateId=&since=&limit=&cursor=`
  - 安全：`@RequireGovernanceAccess`
  - 排序：`occurredAt desc, id desc`
  - 分页：游标分页；`cursor` 为上一页最后一条的 `(occurredAt,id)` 编码
  - 规则：`aggregateId` 传入时必须同时传 `aggregateType`
  - 默认 `limit=50`，最大 `200`

### 2. 控制面事件写入
- 在 API 侧引入统一写入器：
  - `PlatformEventRepository` 负责 JDBC append/query
  - `PlatformEventService` 负责构造事件、生成 `platform-event-*` ID、规范 payload
- `CatalogService` / `KnowledgeService` 在“持久化成功后”追加平台事件，`actorId` 统一取 `CurrentUserResolver.resolveCurrentUser().id()`
- 首批记录的控制面事件：
  - `DOMAIN_CREATED / UPDATED / DELETED`
  - `SCENARIO_CREATED / UPDATED / DELETED`
  - `ASSISTANT_CREATED / UPDATED / DELETED`
  - `ASSISTANT_RELEASE_PUBLISHED`
  - `AGENT_CREATED / UPDATED / DELETED`
  - `PLAYBOOK_CREATED / UPDATED / DELETED`
  - `RESOURCE_CREATED / UPDATED / DELETED`
  - `RESOURCE_VERSION_CREATED / UPDATED / DELETED / PUBLISHED`
  - `KNOWLEDGE_BASE_CREATED / UPDATED / DELETED`
  - `KNOWLEDGE_RELEASE_CREATED / DELETED / PUBLISHED`
- 聚合归属规则：
  - 版本/发布类事件挂到父对象聚合上，便于对象页一次查全历史
  - 例如资源版本事件使用 `aggregateType=RESOURCE, aggregateId=resourceId`
- payload 统一只放审计与定位字段，不放整对象快照/完整 diff：
  - 通用：`name`
  - 版本/发布：`versionId / version / status / releaseId / snapshotId`
  - 删除：必要时保留 `deletedObjectId / deletedObjectName`
- `updateAssistant` 若同次请求触发发布：
  - 仍写 `ASSISTANT_UPDATED`
  - 额外再写一条 `ASSISTANT_RELEASE_PUBLISHED`

### 3. 运行态审计纳入统一边界
- 不在 repository 层推断业务语义；在 worker 的 `SessionWorkflowImpl` 业务转移点显式写平台事件，避免从投影反推 actor/source
- 扩展 `SessionPersistenceActivities` 增加 `appendPlatformEvent(...)`，由 worker 侧 JDBC append 实现落库
- 运行态事件策略：
  - 每次 `appendEvent(SessionEvent)` 同步写一条 `SESSION_EVENT_RECORDED`
    - `aggregateType=SESSION`
    - `aggregateId=sessionId`
    - `actorId=sessionEvent.actorId`
    - payload：`sessionEventId / sessionEventType / actorType / relatedPlaybookRunId / relatedOwnerAgentId`
  - 每次 playbook run 发生有效状态迁移时写一条 `PLAYBOOK_RUN_STATUS_CHANGED`
    - `aggregateType=PLAYBOOK_RUN`
    - `aggregateId=runId`
    - `actorId` 取当前触发上下文能确定的 actor；无法确定时允许为 `null`
    - payload：`sessionId / playbookId / ownerAgentId / previousStatus / currentStatus / waitingReason / parentSessionEventId / sourceEventId`
- 只在“状态或 waitingReason 真正变化”时记 playbook run 审计，避免重复 upsert 产生噪音
- 现有 `session_runtime_event` 和 `session_runtime_playbook_run` 继续作为运行查询投影，职责不变

## UI / API Consumption
- API 新增 `PlatformEventController`
- Web 新增 `api.listPlatformEvents(...)` 与对应 TS 类型
- 新增可复用组件 `ObjectHistoryPanel`
  - 输入：`aggregateType / aggregateId / reloadKey`
  - 展示：事件名、时间、actorId、payload 摘要/JSON 展开、`加载更多`
- 页面接入：
  - `DomainPage / ScenarioPage / AssistantPage / AgentPage / PlaybookPage / ResourceLibraryPage`：在现有 `ObjectReferencePanel` 下方增加 `ObjectHistoryPanel`
  - `KnowledgeLibraryPage`：新增“操作历史” tab
  - `RuntimeConversationPage` 暂不接入，继续使用现有 runtime timeline

## Test Plan
- 数据层：
  - migration 能创建 `platform_event` 与索引
  - JDBC query 能正确处理 `aggregateType + aggregateId + since + cursor + limit`
- 服务层：
  - catalog/knowledge 关键 CRUD 与 publish 操作会写正确事件和 `actorId`
  - `updateAssistant` 触发发布时产生 `ASSISTANT_UPDATED + ASSISTANT_RELEASE_PUBLISHED`
- worker：
  - session event 会写 `SESSION_EVENT_RECORDED`
  - playbook run 仅在有效状态迁移时写 `PLAYBOOK_RUN_STATUS_CHANGED`
  - 重复 save/upsert 不重复产生日志
- 鉴权：
  - `/api/events` 对治理角色可读，对 `BUSINESS_USER` 拒绝
- 前端：
  - `ObjectHistoryPanel` 首次加载、加载更多、空态、错误态
  - `reloadKey` 变化时重新查询

## Assumptions
- 本任务不实现归档/恢复，所以只预留 archive 类事件名，不落实际写入。
- 知识库上传、URL 导入、文档删除、快照创建/重试、导入重试不进入首批平台事件日志。
- 审计 payload 首版不做 before/after diff，只保证可追责、可排障、可定位对象与版本。
- 完成实现后同步更新 OpenAPI，以及收敛 `docs/project_todos.md` §2.1 和 `docs/todo/runtime_todo.md` §4 的待办表述。
