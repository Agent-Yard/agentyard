# Messages Refactor Execution Plan

## Overview

这次重构跨 `apps/api`、`apps/worker`、`apps/agent-runtime`、`apps/channel-gateway`、`apps/web`、`packages/contracts`、`packages/contracts-jvm`、`packages/persistence-jvm` 和 `packages/extension-protocol`。执行必须先定契约和持久化状态机，再替换运行链路。

## Phase 1: Contracts And Schema Foundation

### Task 1: Define Turn Contracts

**Description:** 更新 OpenAPI、JVM contracts、TS contracts、Python models，明确 Web turn、channel inbound turn、workflow `UserTurn`、agent-runtime delta request 的最终结构。

**Acceptance criteria:**
- [ ] Web public request 不允许客户端传 `role/sender`
- [ ] Trusted import request 允许可信服务端导入 `USER/ASSISTANT/HUMAN_OPERATOR/SYSTEM`
- [ ] Channel/internal request 支持每条 message 的 `role/sender/externalMessageId/occurredAt`
- [ ] `SessionMessage` 暴露 `turnId/turnIndex/producerType/externalMessageId/clientMessageId/occurredAt`
- [ ] Agent-runtime contract 移除 `recentMessages/recentEvents/triggerMessageId`

**Verification:**
- [ ] `./gradlew :packages:contracts-jvm:test`
- [ ] `pnpm --filter @lynxus/extension-protocol self-check`
- [ ] `pnpm --filter @lynxus/web build`
- [ ] `uv run pytest apps/agent-runtime/tests/test_models.py -q`

**Dependencies:** None

**Files likely touched:**
- `packages/contracts/openapi/control-plane.yaml`
- `packages/contracts/openapi/channel-gateway-internal.yaml`
- `packages/contracts/src/index.ts`
- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/session/SessionContracts.java`
- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/channel/ChannelContracts.java`
- `apps/agent-runtime/lynxus_agent_runtime/models.py`

**Estimated scope:** M

### Task 2: Add Session Turn Persistence Schema

**Description:** 为 session active identity、turn state、message producer/order 字段和 channel inbound turn audit 增加 migrations，并重新生成 jOOQ 代码。

**Acceptance criteria:**
- [ ] `session_runtime_session` 有 `entry_scope/channel_profile_id/external_conversation_id/next_message_sequence/shared_state_revision`
- [ ] active Web partial unique index 覆盖非 `ENDED` 的 `(customer_id, assistant_id)`
- [ ] active Channel partial unique index 覆盖非 `ENDED` 的 `(channel_profile_id, external_conversation_id, customer_id, assistant_id)`
- [ ] session schema 支持 API 在 workflow start 前写入初始 projection 字段
- [ ] `session_runtime_turn` 能恢复 turn/message ID allocation 与 workflow accepted 状态
- [ ] `session_runtime_message` 有非空 `turn_id/turn_index/producer_type`
- [ ] `(session_id, turn_id, turn_index)` 有唯一约束
- [ ] channel inbound audit 能记录 duplicate message 行而不违反唯一约束

**Verification:**
- [ ] `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'`
- [ ] `./gradlew :apps:channel-gateway:test --tests '*ChannelAdminRepositoryTest*'`

**Dependencies:** Task 1

**Files likely touched:**
- `apps/api/src/main/resources/db/migration/`
- `apps/channel-gateway/src/main/resources/db/migration/`
- `packages/persistence-jvm/src/generated/jooq/`
- `apps/channel-gateway/src/generated/jooq/`

**Estimated scope:** M

### Task 3: Implement API-Owned Session Store, Unified Append And Turn Store

**Description:** 在 persistence 层实现 API-owned create-or-reuse active session、`session_runtime_turn` 状态读写、ID allocation 恢复、批量 append、DB row lock sequence 分配。

**Scope:** 本任务只定义并证明 persistence/store primitive。API send-turn、workflow 平台消息、human operator reply 等业务调用方迁移不属于 Task 3。

**Acceptance criteria:**
- [ ] `createOrReuseActiveSession` 在 workflow start 前写入或复用 `session_runtime_session`
- [ ] 并发首条消息只依赖 DB unique index 保证一个 active session，Redis lock 只是优化
- [ ] 唯一约束冲突时读取已存在 active session 并继续复用，不重新分配 session id
- [ ] worker projection update 只能更新已有 session row，不能 insert 缺失 row
- [ ] worker projection update 不修改 `entry_scope/channel_profile_id/external_conversation_id/customer_id/assistant_id/created_at/next_message_sequence`
- [ ] `appendSessionMessages(sessionId, turnId, messages)` 是唯一 session message write primitive
- [ ] append store 接收已存在的 `turnId`，不负责创建 platform turn，也不负责调用 Temporal
- [ ] append store 支持 `EXTERNAL` 与 `PLATFORM` message 数据形状，并返回落库后的 `SessionMessage`
- [ ] Task 3 不要求 API send-turn、workflow 平台消息、human operator reply 已经迁移到 append store
- [ ] append 事务内不调用 Temporal、agent-runtime、channel provider 或其他外部 IO
- [ ] 同一 session 并发 append 不产生重复 `sequence/turnIndex`
- [ ] `session_runtime_turn.message_ids` 包含该 turn 下全部 message ids

**Verification:**
- [ ] `./gradlew :packages:persistence-jvm:test`
- [ ] `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'`
- [ ] 新增 `createOrReuseActiveSession`、turn store、append 并发单元测试通过

**Dependencies:** Task 2

**Files likely touched:**
- `packages/persistence-jvm/src/main/java/com/lynxus/persistence/session/SessionRuntimeStore.java`
- `apps/api/src/main/java/com/lynxus/platform/session/JooqSessionRuntimeRepository.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionRuntimeRepository.java`

**Estimated scope:** M

### Checkpoint: Foundation

- [ ] Contracts compile
- [ ] API and gateway migrations run in tests
- [ ] New append store tests pass
- [ ] Old direct `appendMessage(SessionMessage)` call sites are identified for replacement

## Phase 2: API And Workflow

### Task 4: Replace Public Send Message With Send Turn And Trusted Import Turn

**Description:** 实现 `/api/session-runtime/turns` 与 trusted `/internal/session-runtime/import-turns`，收敛 create-or-reuse session、turn allocation、message dedupe、message append、Temporal update accepted。

**Acceptance criteria:**
- [ ] `Idempotency-Key` 必须等于 `turnDedupKey`
- [ ] `sessionId` 为空时，API 先按 active identity 创建或复用 session row，再启动 workflow
- [ ] 显式 `sessionId` 请求必须校验 `entry_scope/customerId/assistantId/channelProfileId/externalConversationId` 与目标 session 一致
- [ ] trusted import turn API 支持 existing session、Web identity、Channel identity 三类 target，不新增 `IMPORT` entry scope
- [ ] trusted import turn API 允许 `USER/ASSISTANT/HUMAN_OPERATOR/SYSTEM` role 和 server-trusted sender，Web public API 仍禁止客户端声明 role/sender
- [ ] trusted import messages 写入 `producer_type = EXTERNAL`，并使用 `importMessageId` 或 `externalMessageId` 做消息级幂等
- [ ] imported `ASSISTANT/HUMAN_OPERATOR/SYSTEM` 消息进入 transcript replay，但不会生成 channel final delivery
- [ ] API 初始 session row 持久化 `scenarioId/title/customerId/assistantId/assistantReleaseVersion/primaryAgentId/currentOwnerAgentId/sharedState/idleDeadline` 等 workflow-visible 字段
- [ ] workflow ensure-start 使用 `sessionId` 作为 workflow id；`WorkflowExecutionAlreadyStarted` 视为成功
- [ ] API 崩溃在 session row commit 后、workflow start 前时，重试会复用同一 session row 并重新 ensure-start
- [ ] Web request 空 messages 由 API preflight 拒绝且不创建 turn row
- [ ] `agentTurnActive/draining/ENDED` 由 API preflight 拒绝，不创建 turn row、不 append message、不提交 workflow update
- [ ] API send-turn 使用 `appendSessionMessages` 在 Temporal update 前 append 已接受的外部输入消息
- [ ] DB 幂等重放返回相同 `sessionId/turnId/acceptedMessageIds/duplicateExternalMessageIds`
- [ ] 同一 active identity 并发首条消息只能创建一个 active session
- [ ] Temporal update accepted 后才把 turn 标记为 `WORKFLOW_ACCEPTED`
- [ ] Temporal `@UpdateValidatorMethod` 的竞态拒绝不得被记录为 `WORKFLOW_ACCEPTED`，重试必须复用同一 `turnId/messageId` allocation

**Verification:**
- [ ] `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'`
- [ ] `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'`

**Dependencies:** Tasks 1-3

**Files likely touched:**
- `apps/api/src/main/java/com/lynxus/platform/session/SessionRuntimeDtos.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionRuntimeController.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionRuntimeService.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionDispatchLockService.java`
- `apps/api/src/test/java/com/lynxus/platform/session/`

**Estimated scope:** M

### Task 5: Add Idempotent Workflow UserTurn Update

**Description:** 用 `submitUserTurn(UserTurn)` 替换 `submitUserMessage(UserMessage)`，workflow 接收已落库 message delta，并以 `turnId` 作为 Temporal update id 和 workflow 内部去重键。

**Acceptance criteria:**
- [ ] `SessionStartRequest` 表示 workflow bootstrap，不表示 worker 负责创建 session row
- [ ] workflow `run()` 初始化内存态后只更新已有 session projection；缺失 session row 是 invariant violation
- [ ] workflow projection update 保留 API 写入的 active identity 字段与 `next_message_sequence`
- [ ] workflow 不 append 外部输入消息
- [ ] workflow update accepted 后不再用普通 result 返回 `BUSY/REJECTED`
- [ ] workflow-local busy/draining/ended 竞态通过 Temporal `@UpdateValidatorMethod` 拒绝，拒绝发生在 accepted stage 前
- [ ] 同一 `turnId` 重放返回 accepted/idempotent result，不重复执行 agent-runtime
- [ ] owner switch 多次 model execution 使用同一 `turnId` 和不同 `turnExecutionId`
- [ ] workflow 内 agent/system/operator 平台消息通过 `appendSessionMessages` activity append
- [ ] workflow 消费 append activity 返回的落库后 `SessionMessage` 更新内存态，不再用内存 `messages.size() + 1` 推导最终 `sequence`
- [ ] agent reply/security block/failed reply/decision rejected reply 都复用父 `turnId`

**Verification:**
- [ ] `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'`
- [ ] `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'`

**Dependencies:** Task 4

**Files likely touched:**
- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/session/SessionWorkflow.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionWorkflowGateway.java`
- `apps/worker/src/main/java/com/lynxus/worker/session/SessionWorkflowImpl.java`
- `apps/worker/src/main/java/com/lynxus/worker/session/SessionPersistenceActivitiesImpl.java`

**Estimated scope:** M

### Task 6: Add Platform Turn Allocation For No-External-Input Flows

**Description:** 为 human operator proactive reply、human resume、external callback、playbook completed re-evaluation、system owner wakeup 等无外部输入路径增加 platform turn allocation，并把相关 signal/update 迁移到带 `turnId` 的边界。

**Acceptance criteria:**
- [ ] 提供统一 `allocatePlatformTurn(sessionId, triggerType, dedupKey, sourceEventId, metadata)` store/API/activity 入口，创建或复用 `accepted_input_message_ids = []` 的 `session_runtime_turn`
- [ ] platform turn allocator 只分配 turn，不 append message、不调用 Temporal、不执行 agent-runtime
- [ ] human operator proactive reply 由 API 先分配 platform turn，再 append operator visible message 或 signal workflow，operator message 使用同一 `turnId`
- [ ] human resume、external callback、playbook completed re-evaluation、system owner wakeup 在触发 owner agent 前都有 durable platform turn
- [ ] workflow 内部触发的 platform turn 通过 activity 分配，并把返回的 `turnId` 传入后续 owner cycle
- [ ] platform turn `dedupKey` 映射固定：operator action id、callback idempotency key、resume event id、playbook progress/event id、system session event id
- [ ] 没有天然 dedup source 的 system wakeup 必须先创建 session event，再用 event id 派生 dedup key
- [ ] 平台 turn 初始允许 `message_ids = []`；后续 agent/operator/system 可见输出 append 到同一 turn
- [ ] 同一 platform dedup key 重放返回同一 `turnId`，不重复触发 owner agent

**Verification:**
- [ ] `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'`
- [ ] `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'`
- [ ] `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'`

**Dependencies:** Tasks 3 and 5

**Files likely touched:**
- `packages/persistence-jvm/src/main/java/com/lynxus/persistence/session/SessionRuntimeStore.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionRuntimeService.java`
- `apps/api/src/main/java/com/lynxus/platform/session/SessionWorkflowGateway.java`
- `apps/worker/src/main/java/com/lynxus/worker/session/SessionWorkflowImpl.java`
- `apps/worker/src/main/java/com/lynxus/worker/session/SessionPersistenceActivitiesImpl.java`

**Estimated scope:** M

### Task 7: Generate Context Entries And Migrate Agent Runtime To Delta Transcript

**Description:** workflow 生成 deterministic `contextEntries`，agent-runtime request 改为 `messages + contextEntries + transcriptBootstrap`，移除 recent window 与 trigger message 查找，并补 transcript bootstrap reset 幂等。

**Acceptance criteria:**
- [ ] workflow 为影响模型决策的 session events 生成 `SESSION_EVENT` context entry
- [ ] workflow 为 shared state 更新生成 `SHARED_STATE_PATCH`，revision 来自 `session_runtime_session.shared_state_revision`
- [ ] workflow 在 bootstrap、owner switch、patch history 缺失或 retention/size 边界生成 `SHARED_STATE_SNAPSHOT`
- [ ] workflow 在 playbook start/wait/resume/complete/state changed 时生成 `ACTIVE_PLAYBOOK_SUMMARY`
- [ ] `contextEntries` 按 `(occurredAt, revision, entryId)` 稳定排序，且同一 `entryType + entryId + revision` 幂等
- [ ] 删除 `recentMessages/recentEvents/triggerMessageId` 后，human resume、external callback、shared state、active playbook、owner switch 上下文不丢失
- [ ] 无 committed transcript 时用 `messages + contextEntries` 初始化 prompt
- [ ] 有 committed transcript 时只追加本 turn delta
- [ ] `transcriptBootstrap = true` 时包含足够的 `messages + contextEntries` 重建当前 owner/provider context，而不是只发送当前 user delta
- [ ] transcript store 持久化 `transcript_bootstrap_reset` marker，key 至少覆盖 `turnExecutionId/providerType`
- [ ] bootstrap reset marker 记录 `sessionId/ownerAgentId/ownershipEpoch/executionAttemptId/status/resetCompletedAt/deletedCommittedEntryCount`
- [ ] 同一 `turnExecutionId/providerType` 已完成 reset 的重试不得再次删除 committed transcript，但必须幂等清理 transcript cache
- [ ] 已 `SUCCEEDED` 的 turn execution 不允许再次执行 bootstrap reset
- [ ] `mark_failed` 只 abort 当前 pending entries，不删除 committed transcript，也不清理 completed reset marker
- [ ] `transcriptBootstrap = true` 时按固定顺序 reset -> replay -> commit
- [ ] security 检查覆盖本 turn 所有 `role = USER` 的新增消息
- [ ] agent-runtime 删除或替换 `list_recent_events` context tool；如保留事件查询工具，只能读取 `contextEntries`
- [ ] stream payload 对外使用 `replyMessageId`，JVM contracts、Python models、API stream service、channel relay、Web 类型同步更新
- [ ] Web streamed reply draft 使用 `(turnId, replyMessageId)` 关联，不再依赖 stream `messageId`

**Verification:**
- [ ] `uv run pytest apps/agent-runtime/tests/test_prompting.py -q`
- [ ] `uv run pytest apps/agent-runtime/tests/test_transcript_store.py -q`
- [ ] `uv run pytest apps/agent-runtime/tests/test_agent_turn_streaming.py -q`
- [ ] `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'`

**Dependencies:** Tasks 5-6

**Files likely touched:**
- `apps/agent-runtime/lynxus_agent_runtime/models.py`
- `apps/agent-runtime/lynxus_agent_runtime/prompting.py`
- `apps/agent-runtime/lynxus_agent_runtime/streaming.py`
- `apps/agent-runtime/lynxus_agent_runtime/transcript_store.py`
- `apps/worker/src/main/java/com/lynxus/worker/session/SessionWorkflowImpl.java`
- `apps/worker/src/main/java/com/lynxus/worker/runtime/SessionAgentRuntimeGateway.java`

**Estimated scope:** L, split internally by agent-runtime model/prompt/store if needed

### Checkpoint: Runtime Core

- [ ] Web/API can submit a single user turn through new endpoint
- [ ] Worker executes accepted turn without duplicating external input messages
- [ ] No-external-input platform turns can trigger owner execution without creating external messages
- [ ] Agent-runtime prompt no longer uses recent message/event window
- [ ] Idempotency recovery tests cover interrupted states before and after workflow accepted

## Phase 3: Channel And Web

### Task 8: Replace Channel Message Event With Inbound Turn

**Description:** channel gateway 消息类 inbound 使用 `NormalizedChannelInboundTurn`，非消息 event 继续使用 `NormalizedChannelInboundEvent`，并把 gateway -> API 内部请求改为 `ChannelInboundSessionTurnRequest`.

**Acceptance criteria:**
- [ ] 消息类 webhook 使用 `/internal/channel-turns/normalized`
- [ ] pull-style job response 支持 `inboundTurns[]` 和 `events[]`
- [ ] gateway 写 `channel_inbound_turn + channel_inbound_turn_message`
- [ ] message-class inbound turn 同步 dispatch 到 API；provider response 表示 session-runtime accepted/rejected/duplicate/failed 结果
- [ ] 重复 externalMessageId 记录 duplicate 审计但不重复 dispatch session-runtime
- [ ] mixed duplicate/new turn 只 dispatch new messages，并把 turn 标为 `PARTIALLY_DISPATCHED`
- [ ] all-duplicate turn 标为 `DUPLICATE`，不调用 API
- [ ] API preflight rejection 后 message/turn 标为 `REJECTED`，不进入 queued eventual 状态
- [ ] API dispatch failure after dedupe claim 标为 `FAILED`，retry 只 dispatch 仍为 `RECEIVED` 的消息
- [ ] 附件按图片 `IMAGE`、非图片 `CARD(FILE_ATTACHMENT)` 映射

**Verification:**
- [ ] `./gradlew :apps:channel-gateway:test --tests '*NormalizedChannel*'`
- [ ] `./gradlew :apps:channel-gateway:test --tests '*ChannelInboundSessionDispatcherTest*'`
- [ ] `./gradlew :packages:extension-sdk-jvm:test`
- [ ] `uv run pytest packages/extension-sdk-python/tests/test_extension_protocol_contract.py -q`

**Dependencies:** Tasks 1-4

**Files likely touched:**
- `packages/extension-protocol/openapi/extension-boundary.openapi.json`
- `packages/extension-protocol/json-schema/`
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/channel/`
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/connector/feishu/`
- `packages/extension-sdk-python/`
- `packages/extension-sdk-jvm/`

**Estimated scope:** L, split by contract/gateway/provider tests if needed

### Task 9: Fix Channel Outbound Scope

**Description:** outbound final replay 根据 `producer_type = PLATFORM` 与 active channel identity 投递，防止导入历史消息外发。

**Acceptance criteria:**
- [ ] outbound query 过滤 `producer_type = PLATFORM`
- [ ] outbound 目标来自 session active channel identity 或保持同步的 binding snapshot
- [ ] `EXTERNAL` 的 assistant/operator/system 历史消息不会生成 `FINAL_DELIVERY`
- [ ] `PLATFORM` 的 agent/operator/system 新消息会生成 `FINAL_DELIVERY`

**Verification:**
- [ ] `./gradlew :apps:api:test --tests '*ChannelOutboundFramePublisherTest*'`
- [ ] `./gradlew :apps:api:test --tests '*SessionChannelOutboundRelayTest*'`
- [ ] `./gradlew :apps:api:test --tests '*DefaultSessionChannelActivityRelayTest*'`

**Dependencies:** Tasks 2-8

**Files likely touched:**
- `packages/persistence-jvm/src/main/java/com/lynxus/persistence/session/SessionRuntimeStore.java`
- `apps/api/src/main/java/com/lynxus/platform/channel/ChannelOutboundFramePublisher.java`
- `apps/api/src/main/java/com/lynxus/platform/session/DefaultSessionChannelActivityRelay.java`
- `apps/api/src/main/java/com/lynxus/platform/channel/ChannelBindingSnapshotLookupService.java`

**Estimated scope:** M

### Task 10: Migrate Web Runtime Client And UI

**Description:** Web 运行页发送 turn request，支持多条 draft message 一次提交，并按独立 message bubbles 展示同一 turn 下多条 message。

**Acceptance criteria:**
- [ ] 前端不再调用 `/api/session-runtime/messages`
- [ ] 发送请求包含 `turnDedupKey`，且 `Idempotency-Key` 等于 `turnDedupKey`
- [ ] 每条 draft message 包含稳定 `clientMessageId`
- [ ] Web 通过 `acceptedMessageAllocations[].clientMessageId/messageId/turnIndex` 替换本地 user drafts
- [ ] `acceptedMessageIds` 只作为 convenience list，不能作为唯一 draft reconciliation 来源
- [ ] stream frames 使用 `replyMessageId`，assistant/operator/system reply drafts 以 `(turnId, replyMessageId)` 关联
- [ ] 同一 `turnDedupKey` 重放不会创建重复本地 bubbles，仍复用相同 persisted message ids
- [ ] `SessionMessage` 新字段类型完整
- [ ] UI 同一 turn 下多条 message 独立展示，blocks 渲染不退化

**Verification:**
- [ ] `pnpm --filter @lynxus/web test`
- [ ] `pnpm --filter @lynxus/web build`

**Dependencies:** Tasks 1, 4, and 7

**Files likely touched:**
- `apps/web/src/types/session.types.ts`
- `apps/web/src/services/`
- `apps/web/src/pages/`
- `apps/web/src/components/`

**Estimated scope:** M

### Checkpoint: Entry Points

- [ ] Web send-turn works end-to-end
- [ ] Channel inbound turn dispatches once per batch
- [ ] Channel outbound only delivers platform-produced final messages

## Phase 4: Cleanup And Documentation

### Task 11: Remove Old Single Message Path

**Description:** 删除旧 DTO、旧 endpoints、旧 workflow update、旧 tests 和旧架构文档引用。

**Acceptance criteria:**
- [ ] `SendSessionMessageRequest` 不再是可用 public 写路径
- [ ] `submitUserMessage(UserMessage)` 不再被 API 调用
- [ ] docs 不再指向 `/api/session-runtime/messages`
- [ ] generated SDK/OpenAPI examples 使用 turn 模型

**Verification:**
- [ ] `rg "/api/session-runtime/messages|SendSessionMessageRequest|submitUserMessage|triggerMessageId|trigger_message_id|recentMessages|recentEvents|recent_messages|recent_events|list_recent_events" apps packages docs --glob '!docs/develop_record/**'` 只剩历史说明或无结果
- [ ] `rg "ChannelInboundSessionMessageRequest|ChannelInboundSessionMessageResponse|NormalizedChannelInboundEvent.*message|channel_inbound_event" apps packages docs --glob '!docs/develop_record/**'` 只剩非 message event 路径、generated schema 或明确的迁移说明
- [ ] `./gradlew verifyJooqGenerated`
- [ ] `./gradlew :packages:persistence-jvm:verifyJooqGenerated`
- [ ] `./gradlew :apps:channel-gateway:verifyJooqGenerated`
- [ ] `./gradlew test`
- [ ] `pnpm -r --if-present test`
- [ ] `pnpm --filter @lynxus/web build`
- [ ] `uv run pytest`

**Dependencies:** Tasks 4-10

**Files likely touched:**
- `docs/architecture/`
- `docs/project_structure.md`
- `docs/technical_route.md`
- tests under `apps/api`, `apps/worker`, `apps/channel-gateway`, `apps/agent-runtime`

**Estimated scope:** M

### Final Checkpoint

- [ ] All phase verification commands pass or documented with concrete blocker
- [ ] No old public single-message write path remains
- [ ] New contracts and docs agree on field names and endpoints
- [ ] Code review confirms no external/imported messages can be outbound delivered
