# Messages Refactor Execution Ledger

## Current State

- Current phase/task: Phase 3 Task 8 - Replace Channel Message Event With Inbound Turn
- Main role: coordinate subagent development/review cycles, maintain this ledger, run integration acceptance.
- Development mode: one focused development subagent followed by one independent review subagent per task.
- Source documents read:
  - `docs/todo/messages_refactor.md`
  - `docs/todo/messages_refactor/00_architecture_decisions.md`
  - `docs/todo/messages_refactor/01_execution_plan.md`
  - `docs/todo/messages_refactor/02_contracts_and_persistence.md`
  - `docs/todo/messages_refactor/03_workflow_and_agent_runtime.md`
  - `docs/todo/messages_refactor/04_channel_and_web.md`
  - `docs/todo/messages_refactor/05_verification_matrix.md`

## Global Constraints

- Directly converge to `turn -> messages -> blocks`; no old public single-message write path.
- Do not preserve old database compatibility or gradual dual-path migration.
- API owns initial `session_runtime_session` row before workflow start.
- `session_runtime_turn` is the authoritative idempotency and ID recovery boundary.
- All session message writes go through unified `appendSessionMessages` with DB row locking for `sequence` and `turnIndex`.
- Workflow receives persisted `SessionMessage` delta and does not reallocate/dedupe external input message IDs.
- Web public API cannot accept client-declared `role/sender`; trusted import/channel internal can.
- External/imported messages use `producer_type = EXTERNAL`; platform replies use `producer_type = PLATFORM`.
- Channel active identity includes `channelProfileId + externalConversationId + customerId + assistantId`.
- Agent runtime uses committed transcript plus current turn `messages/contextEntries` delta, not recent windows.
- Do not modify `docs/develop_record/**`.

## Subagent Assignments

- Phase 1 Task 1 development completed and reviewed: `019e3156-5a9d-7711-8c0e-d23c24f3d27d` (Godel), `019e3162-b92a-7132-a0b8-517fd67505fa` (Euler). Both agents closed.
- Phase 1 Task 2 development completed and reviewed: `019e3167-6a8a-7822-a4d2-f63183a4357b` (Singer), `019e316f-01be-7e33-bfea-978838fe1e33` (Tesla). Both agents closed.
- Phase 1 Task 3 development completed and reviewed: `019e3172-81d1-74f1-b08c-09e120876e64` (Sagan), `019e317c-1da1-7ca1-bbfa-dc78f3597100` (Bacon). Both agents closed.
- Phase 1 Foundation checkpoint passed and committed: `0727cbaa feat: add session turn persistence foundation`.
- Phase 2 Task 4 development completed: `019e3182-4808-7632-ab99-4475ca336c5f` (Ampere).
- Phase 2 Task 4 review failed: `019e3195-15a9-70f1-b0eb-81d2eab5fe91` (Aquinas), agent closed.
- Phase 2 Task 4 development completed and reviewed after one fix loop: `019e3182-4808-7632-ab99-4475ca336c5f` (Ampere), `019e3195-15a9-70f1-b0eb-81d2eab5fe91` (Aquinas), `019e319f-fdaf-7642-8543-51b23d534dea` (Pasteur). Agents closed.
- Phase 2 Task 5 development completed: `019e31a3-d4b4-75e3-8711-e63eb25c1827` (Dewey).
- Phase 2 Task 5 review failed: `019e31b2-fbf2-78f2-95e3-91a534272e34` (Dirac), agent closed.
- Phase 2 Task 5 development completed and reviewed after one fix loop: `019e31a3-d4b4-75e3-8711-e63eb25c1827` (Dewey), `019e31b2-fbf2-78f2-95e3-91a534272e34` (Dirac), `019e31bb-5319-7682-b038-448a3b71b971` (Leibniz). Agents closed.
- Phase 2 Task 6 development completed and reviewed after one fix loop: `019e31be-a811-7750-82cd-dbfd4486c4e0` (Fermat), `019e31cd-67c8-7712-9dfd-bae0107000dc` (Meitner), replacement re-review `019e32fa-79a6-7053-9a29-7e3c6d2d75a5` (Boyle). Agents closed.
- Phase 2 Task 7 development completed: `019e3300-f4f3-7040-94c4-d5447623f5e9` (Arendt).
- Phase 2 Task 7 review failed: `019e331c-f8c0-7733-9d9b-74ef863d9558` (Wegener), agent closed.
- Phase 2 Task 7 development completed and reviewed after one fix loop: `019e3300-f4f3-7040-94c4-d5447623f5e9` (Arendt), `019e331c-f8c0-7733-9d9b-74ef863d9558` (Wegener), `019e3327-3f0e-7a62-851a-f7f262c93be7` (Jason). Agents closed.
- Phase 2 Runtime Core checkpoint passed in main. Preparing Phase 3 Task 8 after Phase 2 commit.

## Execution Plan Status

- Phase 1 Task 1 - Define Turn Contracts: passed review
- Phase 1 Task 2 - Add Session Turn Persistence Schema: passed review
- Phase 1 Task 3 - Implement API-Owned Session Store, Unified Append And Turn Store: passed review
- Foundation checkpoint: passed
- Phase 2 Task 4 - Replace Public Send Message With Send Turn And Trusted Import Turn: passed review
- Phase 2 Task 5 - Add Idempotent Workflow UserTurn Update: passed review
- Phase 2 Task 6 - Add Platform Turn Allocation For No-External-Input Flows: passed review
- Phase 2 Task 7 - Generate Context Entries And Migrate Agent Runtime To Delta Transcript: passed review
- Runtime Core checkpoint: passed
- Phase 3 Task 8 - Replace Channel Message Event With Inbound Turn: pending development
- Phase 3 Task 9 - Fix Channel Outbound Scope: pending
- Phase 3 Task 10 - Migrate Web Runtime Client And UI: pending
- Entry Points checkpoint: pending
- Phase 4 Task 11 - Remove Old Single Message Path: pending
- Final checkpoint: pending

## Key Decisions

- `docs/todo/messages_refactor/02_api_contracts.md` referenced by the first read attempt does not exist; actual document is `02_contracts_and_persistence.md`.

## Documentation Questions / Potential Conflicts

- None currently blocking.

## Active Review Findings

- None currently open.

## Cross-Phase Follow-Ups

- Ensure final cleanup searches exclude `docs/develop_record/**`.
- Ensure final Node verification uses `pnpm -r --if-present test` or scoped web tests, not root `pnpm test`.
- Track replacement of stream outward `messageId` with `replyMessageId` across JVM contracts, Python models, API stream services, channel relay, and Web.
- Review noted stream payloads still expose `messageId`; keep for Task 7, not Task 1.
- Review noted `UserMessage` / old single-message workflow records remain; keep for Tasks 5 and 11.
- Baseline cleanup scan found active old single-message API/workflow/runtime references in `apps/api`, `apps/worker`, `apps/web`, `apps/agent-runtime`, and contracts. These are expected before Tasks 4, 5, 7, 10, and 11.
- Baseline channel cleanup scan found active `ChannelInboundSessionMessageRequest/Response` and message-class `NormalizedChannelInboundEvent` usage in API and channel gateway. These are expected before Task 8.
- Task 2 development added temporary schema plumbing in `SessionRuntimeStore.saveSession` to write `entry_scope = WEB` for current legacy callers; Task 3 must replace this with formal API-owned create-or-reuse and worker projection update boundaries.
- Task 2 review found `SessionRuntimeStore.findActiveSession(customerId, assistantId)` still lacks `entry_scope = WEB`; Task 3 must add scope-aware active identity lookup or replace it.
- Task 2 review found legacy `saveSession` upsert still updates identity fields; Task 3 worker projection update must preserve `entry_scope/channel_profile_id/external_conversation_id/customer_id/assistant_id/created_at/next_message_sequence`.
- Task 3 review identified old direct `appendMessage` path in `SessionRuntimeRepository`, `SessionRuntimeStore`, and worker projection repository. Task 4/5 must migrate runtime callers to `appendSessionMessages`; Task 11 must remove old path.
- Task 3 review identified need for narrow turn status update primitive for `WORKFLOW_ACCEPTED / REJECTED / FAILED`; track under Task 4.

## Verification Log

- Phase 1 Task 1 development reported:
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `pnpm --filter @lynxus/extension-protocol self-check` passed.
  - `pnpm --filter @lynxus/web build` passed.
  - `uv run pytest apps/agent-runtime/tests/test_models.py -q` passed.
- Phase 1 Task 1 review repeated and passed the same four verification commands.
- Phase 1 Task 2 development reported:
  - `./gradlew generateJooq` passed.
  - `./gradlew :packages:persistence-jvm:verifyJooqGenerated :apps:channel-gateway:verifyJooqGenerated` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `./gradlew :apps:channel-gateway:test --tests '*ChannelAdminRepositoryTest*'` passed.
- Phase 1 Task 2 review repeated:
  - `./gradlew :packages:persistence-jvm:verifyJooqGenerated :apps:channel-gateway:verifyJooqGenerated` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*' --rerun-tasks` passed.
  - `./gradlew :apps:channel-gateway:test --tests '*ChannelAdminRepositoryTest*' --rerun-tasks` passed.
- Phase 1 Task 3 development reported:
  - `./gradlew :packages:persistence-jvm:test` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `git diff --check` passed.
  - Extra attempted `./gradlew :apps:worker:compileJava` failed because Task 1 contract changes require later Task 5/7 workflow/runtime migration; tracked as expected cross-task breakage, not Task 3 blocker.
- Phase 1 Task 3 review repeated:
  - `./gradlew :packages:persistence-jvm:test` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*' --rerun-tasks` passed.
  - `./gradlew :packages:persistence-jvm:verifyJooqGenerated :apps:channel-gateway:verifyJooqGenerated` passed.
  - `./gradlew :apps:channel-gateway:test --tests '*ChannelAdminRepositoryTest*' --rerun-tasks` passed.
  - `./gradlew :packages:contracts-jvm:test --rerun-tasks` passed.
  - `git diff --check` passed.
- Foundation checkpoint run in main:
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `pnpm --filter @lynxus/extension-protocol self-check` passed.
  - `pnpm --filter @lynxus/web build` passed.
  - `uv run pytest apps/agent-runtime/tests/test_models.py -q` passed.
  - `./gradlew :packages:persistence-jvm:test` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `./gradlew :apps:channel-gateway:test --tests '*ChannelAdminRepositoryTest*'` passed.
  - `./gradlew :packages:persistence-jvm:verifyJooqGenerated :apps:channel-gateway:verifyJooqGenerated` passed.
  - `git diff --check` passed.
  - Old direct `appendMessage` call sites identified in `SessionRuntimeRepository`, `SessionRuntimeStore`, worker persistence activities/workflow, and API tests; migrate/remove in Tasks 5 and 11.
- Phase 2 Task 4 development reported:
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:api:test --tests '*InternalSessionRuntimeControllerTest*'` passed.
  - `git diff --check` passed.
- Phase 2 Task 4 review repeated verification and found implementation blockers:
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*InternalSessionRuntimeControllerTest*'` passed.
  - `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:api:test --tests '*ApiAuthorizationTest*' --tests '*ApiLogContextFilterTest*'` passed.
  - `git diff --check` passed.
- Phase 2 Task 4 fix development reported:
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:api:test --tests '*InternalSessionRuntimeControllerTest*'` passed.
  - `git diff --check` passed.
- Phase 2 Task 4 re-review passed:
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'` passed.
  - `./gradlew :apps:api:test --tests '*InternalSessionRuntimeControllerTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `git diff --check` passed.
- Phase 2 Task 5 development reported:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*' --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `git diff --check` passed.
- Phase 2 Task 5 review found blockers:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*' --rerun-tasks` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*' --rerun-tasks` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*' --rerun-tasks` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `git diff --check` passed.
- Phase 2 Task 5 fix development reported:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `git diff --check` passed.
- Phase 2 Task 5 re-review passed:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `git diff --check` passed.
- Phase 2 Task 6 development reported:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `git diff --check` passed.
- Phase 2 Task 6 review found blocker:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `git diff --check` passed.
- Phase 2 Task 6 fix development reported:
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionWorkflowGatewayTest*'` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `git diff --check` passed.
- Phase 2 Task 6 replacement re-review passed:
  - `./gradlew :apps:api:test --tests '*JooqSessionRuntimeRepositoryTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `git diff --check` passed.
- Phase 2 Task 7 development reported:
  - `uv run pytest apps/agent-runtime/tests/test_prompting.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_transcript_store.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_agent_turn_streaming.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_models.py -q` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*'` passed.
  - `pnpm --filter @lynxus/web build` passed.
  - Touched-path API/channel/contracts checks passed.
  - `git diff --check` passed.
  - Residual risk reported: missing patch history/retention detection is conservative; snapshots generated on bootstrap/provider rebuild/owner switch and patch count/size boundary, but no separate external history-retention detector yet.
- Phase 2 Task 7 review found blocker:
  - `uv run pytest apps/agent-runtime/tests/test_prompting.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_transcript_store.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_agent_turn_streaming.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_models.py -q` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `pnpm --filter @lynxus/web build` passed.
  - `git diff --check` passed.
- Phase 2 Task 7 fix development reported:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `uv run pytest apps/agent-runtime/tests/test_transcript_store.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_prompting.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_agent_turn_streaming.py -q` passed.
  - `pnpm --filter @lynxus/web build` passed.
  - `git diff --check` passed.
- Phase 2 Task 7 re-review passed:
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `uv run pytest apps/agent-runtime/tests/test_transcript_store.py -q` passed.
  - `uv run pytest apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_agent_turn_streaming.py apps/agent-runtime/tests/test_models.py -q` passed.
  - `git diff --check` passed.
- Runtime Core checkpoint run in main:
  - `./gradlew :apps:api:test --tests '*SessionRuntimeServiceTest*'` passed.
  - `./gradlew :apps:api:test --tests '*MultiInstanceApiIntegrationTest*'` passed.
  - `./gradlew :apps:api:test --tests '*InternalSessionRuntimeControllerTest*' --tests '*SessionWorkflowGatewayTest*'` initially failed because NDJSON fixture still used old stream payload; fixed test fixture to use `replyMessageId` and `inputMessageCount`, then passed.
  - `./gradlew :apps:worker:test --tests '*SessionWorkflowImplTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionPersistenceActivitiesImplTest*' --tests '*SessionAgentRuntimeGatewayTest*'` passed.
  - `uv run pytest apps/agent-runtime/tests/test_prompting.py apps/agent-runtime/tests/test_transcript_store.py apps/agent-runtime/tests/test_agent_turn_streaming.py apps/agent-runtime/tests/test_models.py -q` passed.
  - `pnpm --filter @lynxus/web build` passed.
  - `./gradlew :packages:contracts-jvm:test` passed.
  - `./gradlew :apps:api:test --tests '*DefaultSessionChannelActivityRelayTest*' --tests '*SessionRuntimeStreamServiceTest*'` passed.
  - `./gradlew :apps:channel-gateway:test --tests '*GatewayNativeChannelOutboundFrameDispatcherTest*' --tests '*ChannelOutboundRelayComponentsTest*' --tests '*ChannelOutboundUpstreamRelaySupervisorTest*' --tests '*FeishuGatewayNativeChannelProviderAdapterTest*'` passed.
  - `./gradlew :apps:worker:test --tests '*SessionAgentRuntimeGatewayTest*'` passed after diagnostic text fix from `payload.messageId` to `payload.replyMessageId`.
  - `rg "payload\\.messageId|triggerMessageId|recentMessages|recentEvents|recent_messages|recent_events|list_recent_events" ...` shows only agent-runtime negative model tests for forbidden legacy fields.
  - `git diff --check` passed.
