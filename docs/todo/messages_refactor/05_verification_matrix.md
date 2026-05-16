# Messages Refactor Verification Matrix

## 1. Contract Verification

- [ ] OpenAPI contains `/api/session-runtime/turns`
- [ ] OpenAPI contains trusted `/internal/session-runtime/import-turns`
- [ ] OpenAPI no longer advertises `/api/session-runtime/messages`
- [ ] JVM contracts expose `SendSessionTurnRequest`, `TrustedImportSessionTurnRequest`, `UserTurn`, `SessionMessageProducerType`, `NormalizedChannelInboundTurn`
- [ ] TS contracts expose the same fields and enum values
- [ ] Python agent-runtime models no longer use `recentMessages/recentEvents/triggerMessageId`
- [ ] Agent-runtime context tools no longer expose `list_recent_events` backed by `recentEvents`

Commands:

- [ ] `./gradlew :packages:contracts-jvm:test`
- [ ] `pnpm --filter @lynxus/extension-protocol self-check`
- [ ] `pnpm --filter @lynxus/web build`
- [ ] `uv run pytest apps/agent-runtime/tests/test_models.py -q`

## 2. Persistence Verification

- [ ] API-owned create-or-reuse writes `session_runtime_session` before workflow start
- [ ] Same active identity concurrent first-turn requests create one active session
- [ ] Same active identity concurrent first-turn requests do not allocate different session ids when Redis lock is unavailable
- [ ] Different channel `externalConversationId` values create different active sessions
- [ ] Unique index conflict path reads and reuses the already-created active session
- [ ] API retry after crash between session row commit and workflow start reuses the session row and can ensure workflow start
- [ ] Worker projection update fails visibly if the session row is missing
- [ ] Worker projection update does not overwrite active identity fields or `next_message_sequence`
- [ ] Turn row allocated but messages not appended can recover with same ids
- [ ] Messages appended but workflow not accepted can recover without duplicate rows
- [ ] Workflow accepted but API response lost can replay same response
- [ ] Batch append allocates continuous `sequence`
- [ ] Same `(sessionId, turnId, turnIndex)` cannot be duplicated
- [ ] Duplicate `externalMessageId` does not append a second session message

Commands:

- [ ] `./gradlew :packages:persistence-jvm:test`
- [ ] `./gradlew :packages:persistence-jvm:verifyJooqGenerated`
- [ ] `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'`
- [ ] `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'`
- [ ] `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'`

## 3. API Verification

- [ ] Empty Web `messages[]` is rejected by API preflight and creates no turn row
- [ ] Web request with `assistantId` creates or reuses an API-owned active Web session row before workflow start
- [ ] Web request with `sessionId` validates customer, assistant, entry scope, and absence of channel identity fields
- [ ] Channel/internal turn request creates or reuses active Channel session by `channelProfileId + externalConversationId + customerId + assistantId`
- [ ] Explicit channel `sessionId` validates channel profile and external conversation identity
- [ ] Trusted import turn target validates existing session, Web identity, or Channel identity without introducing `IMPORT` entry scope
- [ ] Trusted import turn accepts `ASSISTANT/HUMAN_OPERATOR/SYSTEM` roles while Web public API rejects role/sender input
- [ ] Trusted import message requires `importMessageId` or `externalMessageId` and derives canonical source message id for idempotent replay
- [ ] Trusted import messages are persisted as `producer_type = EXTERNAL`
- [ ] Workflow ensure-start is idempotent; already-started workflow is treated as success
- [ ] Web request cannot write `ASSISTANT/SYSTEM/HUMAN_OPERATOR` messages
- [ ] Busy/draining/ended session is rejected by API preflight with no turn row, no message append, and no workflow update
- [ ] Same `turnDedupKey` replay returns same `turnId/acceptedMessageIds`
- [ ] All-duplicate turn returns duplicate result and does not call workflow
- [ ] Mixed duplicate/new turn calls workflow once with only new messages
- [ ] Temporal `@UpdateValidatorMethod` rejection is not recorded as `WORKFLOW_ACCEPTED` and retry reuses the same allocation

Commands:

- [ ] `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'`
- [ ] `./gradlew :apps:api:test --tests '*InternalSessionRuntimeControllerTest*'`

## 4. Workflow Verification

- [ ] `SessionStartRequest` bootstraps workflow state for an API-created session row
- [ ] workflow `run()` does not insert the authoritative initial session row
- [ ] workflow projection update preserves active identity and `next_message_sequence`
- [ ] `submitUserTurn` does not append external input messages
- [ ] `submitUserTurn` accepted execution does not return business `BUSY/REJECTED`
- [ ] Workflow-local busy/draining/ended guard rejects through Temporal `@UpdateValidatorMethod` before accepted stage
- [ ] Same `turnId` accepted update replay is idempotent inside workflow
- [ ] One accepted turn triggers one owner turn cycle
- [ ] Owner switch keeps same `turnId` and increments `turnExecutionId`
- [ ] Agent reply/security block/failed reply reuse parent `turnId`
- [ ] Workflow platform messages append through `appendSessionMessages` and consume persisted `SessionMessage` rows
- [ ] Human operator proactive reply creates or reuses a platform turn before visible operator message or workflow signal
- [ ] Human resume creates or reuses a platform turn before owner wakeup
- [ ] External callback creates or reuses a platform turn before owner wakeup
- [ ] Playbook completed re-evaluation creates or reuses a platform turn before owner wakeup
- [ ] System owner wakeup creates session event first when no natural dedup source exists, then derives platform turn dedup key
- [ ] Same platform dedup key replay returns same `turnId` and does not duplicate owner agent execution

Commands:

- [ ] `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'`
- [ ] `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'`

## 5. Agent Runtime Verification

- [ ] Prompt renderer converts all current delta messages by role and order
- [ ] Current USER message security scope covers all user messages in current delta
- [ ] Shared state is not rendered every turn unless represented by `contextEntries`
- [ ] `SESSION_EVENT` context entries preserve human resume payloads after `recentEvents` removal
- [ ] `SESSION_EVENT` context entries preserve external callback payloads after `recentEvents` removal
- [ ] `SHARED_STATE_PATCH` entries render changed keys with monotonic `shared_state_revision`
- [ ] `SHARED_STATE_SNAPSHOT` entries render on bootstrap, owner switch, or missing patch history
- [ ] `ACTIVE_PLAYBOOK_SUMMARY` entries render playbook start/wait/resume/complete state
- [ ] No-user platform turns can provide trigger context through `contextEntries`
- [ ] Context entries are ordered by `(occurredAt, revision, entryId)`
- [ ] Context entries are committed and replayed idempotently in later turns
- [ ] Runtime event context tools are removed or backed only by current request `contextEntries`, not `recentEvents`
- [ ] Bootstrap request includes enough `messages + contextEntries` to rebuild current owner/provider context
- [ ] Bootstrap reset order is `begin_execution -> reset -> load -> commit_success`
- [ ] Bootstrap reset persists `transcript_bootstrap_reset` marker keyed by `(turnExecutionId, providerType)`
- [ ] Retry after reset completed but before commit does not delete committed transcript again
- [ ] Retry after successful commit returns cached/final outcome and does not reset
- [ ] Failed execution retry does not delete committed transcript rebuilt by an earlier attempt
- [ ] Bootstrap reset invalidates transcript cache even when completed reset marker already exists
- [ ] Bootstrap reset for one provider type does not delete another provider type's committed transcript
- [ ] Already `SUCCEEDED` turn execution cannot perform bootstrap reset
- [ ] `mark_failed` aborts only pending entries and preserves committed transcript plus completed reset marker
- [ ] Same `turnExecutionId` bootstrap retry does not reset committed transcript twice
- [ ] Stream payload fields use `replyMessageId` in JVM contracts, Python models, API stream services, channel relay, and Web types
- [ ] Web streamed reply drafts bind by `(turnId, replyMessageId)` and do not expect stream `messageId`

Commands:

- [ ] `uv run pytest apps/agent-runtime/tests/test_prompting.py -q`
- [ ] `uv run pytest apps/agent-runtime/tests/test_transcript_store.py -q`
- [ ] `uv run pytest apps/agent-runtime/tests/test_agent_turn_streaming.py -q`
- [ ] `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'`

## 6. Channel Verification

- [ ] `NormalizedChannelInboundTurn` validates provider/profile/conversation fields
- [ ] A multi-message inbound turn dispatches once
- [ ] Message-class inbound turn dispatch is synchronous and provider response reflects session-runtime result
- [ ] All-duplicate inbound turn does not dispatch to API
- [ ] All-duplicate inbound turn finalizes as `DUPLICATE`
- [ ] Mixed duplicate/new inbound turn dispatches only new messages
- [ ] Mixed duplicate/new inbound turn finalizes as `PARTIALLY_DISPATCHED`
- [ ] `channel_inbound_turn_message` records duplicates for audit
- [ ] API preflight rejection marks non-duplicate messages and turn as `REJECTED`
- [ ] API dispatch failure after dedupe claim marks turn `FAILED`
- [ ] Retry after dispatch failure reuses audit rows and dispatches only messages still in `RECEIVED`
- [ ] Retry never redispatches messages already marked `ACCEPTED` or `DUPLICATE`
- [ ] Different `externalConversationId` values do not share active session
- [ ] Image attachments map to `IMAGE`
- [ ] Non-image attachments map to `CARD(FILE_ATTACHMENT)`
- [ ] Non-message events remain in `channel_inbound_event` and do not call session-runtime

Commands:

- [ ] `./gradlew :apps:channel-gateway:test --tests '*NormalizedChannel*'`
- [ ] `./gradlew :apps:channel-gateway:test --tests '*ChannelInboundSessionDispatcherTest*'`
- [ ] `./gradlew :apps:channel-gateway:test --tests '*RemoteProviderJobExecutorTest*'`
- [ ] `./gradlew :apps:channel-gateway:verifyJooqGenerated`

## 7. Outbound Verification

- [ ] `producer_type = EXTERNAL` assistant/operator/system imported messages generate no `FINAL_DELIVERY`
- [ ] Trusted import `ASSISTANT/HUMAN_OPERATOR/SYSTEM` messages are visible in transcript replay but generate no `FINAL_DELIVERY`
- [ ] `producer_type = PLATFORM` assistant/operator/system messages generate `FINAL_DELIVERY`
- [ ] Outbound final replay target uses active channel identity
- [ ] Transient draft/typing frames still route by session binding

Commands:

- [ ] `./gradlew :apps:api:test --tests '*ChannelOutboundFramePublisherTest*'`
- [ ] `./gradlew :apps:api:test --tests '*SessionChannelOutboundRelayTest*'`
- [ ] `./gradlew :apps:api:test --tests '*DefaultSessionChannelActivityRelayTest*'`

## 8. Web Verification

- [ ] Web client calls `/api/session-runtime/turns`
- [ ] Web sends stable `turnDedupKey` and matching `Idempotency-Key`
- [ ] Web sends `clientMessageId` per message
- [ ] Web does not send `role/sender`
- [ ] Web reconciles user drafts from `acceptedMessageAllocations[].clientMessageId/messageId/turnIndex`
- [ ] `acceptedMessageIds` remains a convenience list and is not the only draft reconciliation source
- [ ] Idempotent send replay reuses the same persisted message ids and creates no duplicate local bubbles
- [ ] Web binds assistant/operator/system streamed drafts by `(turnId, replyMessageId)`
- [ ] Same turn multiple messages render as independent bubbles
- [ ] Existing blocks render normally

Commands:

- [ ] `pnpm --filter @lynxus/web test`
- [ ] `pnpm --filter @lynxus/web build`

## 9. Repository-Wide Cleanup And Final Verification

Run after implementation:

```bash
rg "/api/session-runtime/messages|SendSessionMessageRequest|submitUserMessage|triggerMessageId|trigger_message_id|recentMessages|recentEvents|recent_messages|recent_events|list_recent_events" apps packages docs --glob '!docs/develop_record/**'
```

Expected result:

- No active code references remain
- Documentation references only appear when explicitly describing removed historical behavior

Run channel cleanup search after Task 8:

```bash
rg "ChannelInboundSessionMessageRequest|ChannelInboundSessionMessageResponse|NormalizedChannelInboundEvent.*message|channel_inbound_event" apps packages docs --glob '!docs/develop_record/**'
```

Expected result:

- `ChannelInboundSessionMessageRequest/Response` no longer appear in active API/gateway code
- `NormalizedChannelInboundEvent` remains only for non-message channel events
- `channel_inbound_event` remains only for non-message event storage, generated schema, or explicit migration notes

Generated code verification:

- [ ] `./gradlew verifyJooqGenerated`
- [ ] `./gradlew :packages:persistence-jvm:verifyJooqGenerated`
- [ ] `./gradlew :apps:channel-gateway:verifyJooqGenerated`

Final command policy:

- Use `pnpm -r --if-present test` or `pnpm --filter @lynxus/web test`
- Do not use root `pnpm test`; the root package currently has no `test` script
