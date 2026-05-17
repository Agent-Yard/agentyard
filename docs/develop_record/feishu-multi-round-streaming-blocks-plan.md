# 飞书多轮模型流式多 block 执行计划

## 背景

本地复现的飞书问题不是“丢了第二条 assistant 消息”，而是一条 assistant message 内包含多个 TEXT block：

1. round-0 先输出一段文本，并触发工具调用。
2. 工具完成后 round-1 再输出最终文本。
3. Web 端能看到最终 message 的多个 block。
4. 飞书端 streaming 过程中能看到两段文本，但 card 关闭时内容被 round-0 的第一段覆盖。

当前根因是 agent-runtime 在整轮执行中固定使用 `reply-block-1` 发所有 customer delta；但最后 `REPLY_BLOCK_COMPLETED` 只提交 `decision.replyMessage.blocks[0]`。Feishu adapter 收到 `DRAFT_COMPLETE` 后用 completed block 更新并关闭 card，导致已流式追加出的完整内容被第一段覆盖。

## 目标语义

- 一个 session reply message 仍然只对应一个 `replyMessageId`。
- 每个产生 customer-visible 文本的模型 round 对应一个独立 reply block，例如 `reply-block-1`、`reply-block-2`。
- 纯 tool call round 不分配 reply block，不发送 `REPLY_BLOCK_DELTA`，也不发送 `REPLY_BLOCK_COMPLETED`。
- Feishu 对同一个 reply message 使用一张 interactive card。
- Feishu card 以 markdown 文本渲染多个文本 block，block 之间用一个空行分隔。
- Feishu renderer 只拼接非空且可渲染的 TEXT/RICH_TEXT block，使用 `\n\n` 分隔；跳过或降级的非文本 block 不应产生额外首尾空行。
- 流式阶段继续更新同一张 card；新 block 的第一段 delta 到来时，在前一个 block 后追加空行。
- `DRAFT_COMPLETE` 表示某个 block 的草稿完成，不再表示整个 card/回复完成。
- 只有 `TYPING_STOP`、`TURN_COMPLETED` 或 final delivery 才关闭 Feishu streaming mode。

## 层级映射

- `REPLY_BLOCK_DELTA` 和 `REPLY_BLOCK_COMPLETED` 是 agent-runtime 到 API 的 runtime stream frame。
- `DRAFT_UPDATE` 和 `DRAFT_COMPLETE` 是 API relay 到 channel-gateway/provider 的 channel outbound frame。
- API 映射关系为：`REPLY_BLOCK_DELTA -> DRAFT_UPDATE`，`REPLY_BLOCK_COMPLETED -> DRAFT_COMPLETE`。
- `DRAFT_COMPLETE` 的产生前提是上游确实发出了 `REPLY_BLOCK_COMPLETED`；如果某个模型 round 只产生 tool call，没有用户可见文本，则这条链路不产生任何 draft block frame。

## 非目标

- 不拆成多张飞书 card。
- 不为旧 Redis streaming card state 做兼容迁移；本项目当前规则要求直接面向目标结构。
- 不扩展飞书对 IMAGE/CARD 等非 markdown block 的完整富渲染。本轮先保持 TEXT/RICH_TEXT 可渲染，其他 block 继续按现有策略跳过或降级记录。

## 任务分解

### 1. 固化跨模块语义和回归测试

**说明：** 先用测试把目标语义锁住，避免局部修补再次让 stream frame、API relay、Feishu adapter 语义分叉。

**验收标准：**
- [ ] agent-runtime 测试覆盖“round-0 文本 + tool call + round-1 文本”的流式场景。
- [ ] API relay 测试覆盖 `REPLY_BLOCK_COMPLETED` 只转 `DRAFT_COMPLETE`，不再附带 `TYPING_STOP`。
- [ ] Feishu adapter 测试覆盖同一 card 多 block 追加、空行分隔、最终关闭不覆盖旧内容。

**可能涉及文件：**
- `apps/agent-runtime/tests/test_agent_turn_streaming.py`
- `apps/api/src/test/java/com/lynxus/platform/session/DefaultSessionChannelActivityRelayTest.java`
- `apps/channel-gateway/src/test/java/com/lynxus/channel/gateway/connector/feishu/FeishuGatewayNativeChannelProviderAdapterTest.java`

### 2. 调整 agent-runtime 的 block 分配

**说明：** 将“整轮一个 `reply-block-1`”改成“每个产生用户可见文本的模型 round 一个 block”。`_StreamingOutcomeAccumulator.append_assistant_text()` 当前已经把每个有文本的模型 round 加入最终 `replyMessage.blocks`，stream frame 的 blockId 需要跟这个最终结构对齐。纯 tool call round 只产生工具生命周期 frame，不占用 block 序号。

**验收标准：**
- [ ] round-0 的 customer delta 使用 `reply-block-1`。
- [ ] round-1 的 customer delta 使用 `reply-block-2`。
- [ ] `REPLY_BLOCK_COMPLETED` 只为已产生 customer-visible 文本的 block 发出对应完整 block 内容。
- [ ] `REPLY_BLOCK_COMPLETED.block` 的内容必须来自该 block 已流式输出的 customer-visible 文本，不能直接使用 provider raw `message.content`。
- [ ] 纯 tool call round 不发送 `REPLY_BLOCK_DELTA` 或 `REPLY_BLOCK_COMPLETED`，也不导致下一个可见文本 round 的 block 序号跳号。
- [ ] `FINAL_OUTCOME.result.decision.replyMessage.blocks` 与 stream frame 中的 block 顺序一致。

**可能涉及文件：**
- `apps/agent-runtime/lynxus_agent_runtime/streaming.py`
- `apps/agent-runtime/lynxus_agent_runtime/models.py`
- `apps/agent-runtime/tests/test_agent_turn_streaming.py`

### 3. 修正 API 到 channel outbound 的生命周期映射

**说明：** 当前 `DefaultSessionChannelActivityRelay` 把 `REPLY_BLOCK_COMPLETED` 映射为 `DRAFT_COMPLETE + TYPING_STOP`。多 block 语义下这会过早关闭 Feishu card。需要改成 block-level complete 只发 `DRAFT_COMPLETE`，由 `TURN_COMPLETED` 负责 `TYPING_STOP`。

**验收标准：**
- [ ] `REPLY_BLOCK_COMPLETED` 只生成 `DRAFT_COMPLETE`。
- [ ] `TURN_COMPLETED` 仍生成 `TYPING_STOP`。
- [ ] `ERROR` 仍能生成 `DRAFT_DISCARD + TYPING_STOP`。
- [ ] channel outbound contract 描述同步表达 block-level `DRAFT_COMPLETE`。

**可能涉及文件：**
- `apps/api/src/main/java/com/lynxus/platform/session/DefaultSessionChannelActivityRelay.java`
- `packages/contracts/openapi/control-plane.yaml`
- `packages/contracts/openapi/channel-gateway-internal.yaml`
- `packages/extension-protocol/openapi/extension-boundary.openapi.json`
- `packages/contracts/src/index.ts`
- `packages/contracts-jvm/src/main/java/com/lynxus/contracts/channel/ChannelContracts.java`
- `packages/extension-sdk-python/tests/test_extension_protocol_contract.py`

### 4. 将 Feishu streaming card state 改成 block-aware

**说明：** 当前 Redis state 只有一段 `content`。多 block 下需要记录 block 顺序与每个 block 的内容，渲染时统一 join 为 card markdown。

**目标结构：**
- `key`: `channelProfileId + sessionId + replyMessageId`
- `blocks`: 按出现顺序保存 `blockId/blockType/content/completed`
- `sequence`
- `lastSourceSeq`
- `closed`
- card 外部标识字段保持不变

**处理规则：**
- `DRAFT_UPDATE`: upsert block，追加 delta，重新渲染整张 card。
- `DRAFT_COMPLETE`: 用 completed block 的完整内容更新对应 block，重新渲染整张 card，不关闭 streaming mode。该 frame 只应来自有用户可见文本的 upstream block。
- `TYPING_STOP`: 关闭 streaming mode；如果 card 仍为空，保留现有延迟删除逻辑。
- `FINAL_DELIVERY`: 将 `messageBlocks` 渲染成同样的 markdown，多 block 用空行分隔；如果已有 streaming card，则用 final 内容校准并关闭。

**渲染规则：**
- TEXT block 使用 `text` 字段，RICH_TEXT block 使用 `content` 字段。
- 空字符串、空白字符串、以及当前无法渲染的 block 不进入 markdown parts。
- markdown parts 用 `\n\n` join，不能在开头、结尾或被跳过的 block 位置额外插入空行。
- streaming close、`DRAFT_COMPLETE` 校准和 `FINAL_DELIVERY` 必须共用同一套 Feishu markdown block renderer。

**可能涉及文件：**
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/connector/feishu/FeishuStreamingReplyCardState.java`
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/connector/feishu/FeishuGatewayNativeChannelProviderAdapter.java`
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/connector/feishu/FeishuCardJsonFactory.java`
- `apps/channel-gateway/src/test/java/com/lynxus/channel/gateway/connector/feishu/FeishuGatewayNativeChannelProviderAdapterTest.java`

### 5. 校准 final delivery 和 replay 行为

**说明：** final delivery 来自 DB replay，必须跟 streaming card 使用同一套 block 渲染规则，否则 card 可能在 final replay 时再次被不同格式覆盖。

**验收标准：**
- [ ] streaming card 最终内容与 `session_runtime_message.blocks` 中可渲染文本 block 的渲染结果一致。
- [ ] native final checkpoint 正常推进，不重复覆盖已 ack 的旧 final。
- [ ] final-only 场景也使用同一张 card 渲染多个 block，并以空行分隔。

**可能涉及文件：**
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/connector/feishu/FeishuGatewayNativeChannelProviderAdapter.java`
- `apps/channel-gateway/src/main/java/com/lynxus/channel/gateway/channel/GatewayNativeChannelOutboundFrameDispatcher.java`
- `packages/persistence-jvm/src/main/java/com/lynxus/persistence/session/SessionRuntimeStore.java`

### 6. 端到端验证

**自动化验证：**
- [ ] `uv run pytest tests/test_agent_turn_streaming.py tests/test_models.py -q` in `apps/agent-runtime`
- [ ] `./gradlew --console=plain :apps:api:test --tests com.lynxus.platform.session.DefaultSessionChannelActivityRelayTest`
- [ ] `./gradlew --console=plain :apps:channel-gateway:test --tests com.lynxus.channel.gateway.connector.feishu.FeishuGatewayNativeChannelProviderAdapterTest`
- [ ] `./gradlew --console=plain :apps:api:test`
- [ ] `./gradlew --console=plain :apps:channel-gateway:test`

**本地手工验证：**
- [ ] `pnpm local` 重启后，用会触发工具调用的问题从飞书发送消息。
- [ ] 飞书 streaming 过程中同一张 card 展示 round-0 文本和 round-1 文本，中间有空行。
- [ ] card 停止 streaming 后仍保留完整多 block 内容。
- [ ] Web 端同一轮最终 message 的 block 顺序与飞书一致。

## 风险与处理

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| `DRAFT_COMPLETE` 语义被其他 channel provider 当作“关闭回复”使用 | 可能出现 provider 行为差异 | 先检查所有 native/extension provider 对 `DRAFT_COMPLETE` 的消费；若只有 Feishu native 使用，直接修正；若 extension contract 依赖旧语义，同步更新 contract 描述和测试 |
| Feishu update sequence 与 sourceSeq 去重冲突 | 新 block delta 或 completed block 被误判为旧帧 | block-aware state 仍保留全局 `lastSourceSeq`，并用 ordered frame sourceSeq 验证；同 block coalescing 不跨 block |
| final delivery 与 streaming draft 渲染不一致 | card 最终再次覆盖成不同内容 | 抽出 Feishu markdown block renderer，streaming close 和 final delivery 共用 |
| 中途失败后已有 partial block 如何处理 | 用户可能看到半截内容 | 保持现有错误路径：`ERROR` 触发 `DRAFT_DISCARD + TYPING_STOP`；本轮不引入“隐藏已流式内容”的新策略 |

## 完成定义

- 多模型 round 的 visible text 在 stream frame、最终 `replyMessage.blocks`、Feishu card 三处可渲染文本内容和顺序一致。
- 飞书只发送一张 card，block 间以空行分隔。
- 工具调用前后的两段文本不会在 card 关闭时被第一段覆盖。
- 相关 Python、API、channel-gateway 回归测试全部通过。
