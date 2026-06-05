# 隐私保护与数据安全映射层落地方案

> 这份文档是 [`docs/project_todos.md`](../project_todos.md) §3.6 的详细补充，目标是在 `agent-runtime` 与公网 LLM 之间建立可插拔、可审计、可跨实例共享的隐私映射管道。
> 本方案明确按当前 `session workflow + owner agent + playbook workflow` 主模型落地，不重新引入旧 `task / workflow instance` 语义。

## Summary

- 这不是“加几个脱敏正则”就能交付的需求，而是一条新的运行底座能力。
- 主改动面不是单点 Python runtime，而是 `catalog policy -> assistant release freeze -> worker/runtime contract -> agent-runtime tool loop -> audit/view` 整链路。
- 映射层必须做到“出站脱敏、入站还原、工具参数对称恢复、规则兜底、一致性校验、日志不落原值、会话级稳定映射”。
- 首版建议直接按生产目标设计，不做一次性的本地 patch。
- 不新增资源类型；直接扩展现有 `LLM_MODEL` 资源，增加 `privateDeployment` 配置，并复用现有资源复用与筛选体系。
- 首版 knowledge 链路只做 query restore，不对检索结果做 sanitize；后续进入强合规场景再单独评估。

## 1. 目标边界

覆盖范围：

1. owner turn 的 prompt instruction、runtime messages、loaded skills、tool result
2. 公网 LLM 返回的 `replyContent / accompanyingReply / tool_calls.arguments`
3. `knowledge_search.query` 这类可能包含 placeholder 的下游查询参数恢复
4. 所有会再次进入公网 LLM 上下文的工具返回（knowledge 类 builtin tool 按 §4.3 另行处理，首版结果不脱敏）
5. session 级映射状态、审计事件、运行页统计视图

明确不做：

1. 不把原值写入 `platform_event`
2. 不把映射表做成长期业务数据模型；它是运行态安全状态，不是 catalog 数据
3. 当前知识库返回内容默认视为无需脱敏；首版不对 `hits / chunks` 做出站脱敏

## 2. 核心设计决策

### 2.1 复用现有 `LLM_MODEL` 资源，而不是新增资源类型

不新增 `DATA_SECURITY_PROVIDER` 之类的新资源类型，直接扩展现有 `LLM_MODEL` 资源配置。

建议在 `LlmModelConfigDto` 上只增加：

1. `privateDeployment: boolean`

这样做的原因：

1. 现有资源复用、发布冻结、筛选与引用分析能力可以直接复用
2. 这里选中的仍然是一个普通 LLM 资源，只是它部署在企业边界内，可以被 `PrivacyMapper` 封装层用于占位替换与恢复
3. 真正的隐私映射能力不应堆到资源字段里，而应放在 runtime 的统一封装层里做：规则预处理、LLM 改写、一致性校验、规则兜底

这里的私有模型不是“自带替换协议的专用 mapper 服务”，而是普通 LLM。`agent-runtime` 需要在其外侧封装 `PrivacyMapper`，通过专门提示词驱动它完成 sanitize / restore，并在结果进入主链路前做一致性校验。

### 2.2 复用现有 assistant / agent 的 LLM 继承模式

首版不引入 `domain -> scenario -> assistant -> agent` 的新策略继承链，直接复用当前 LLM 资源的继承语义：

1. assistant 配 `privacyModelResourceId`
2. agent 可选择继承 assistant 默认值
3. agent 也可显式覆盖为另一私有 LLM 资源

除模型选择外，隐私映射行为配置采用扁平字段，只在 assistant / agent 两层解析，不扩展到 domain / scenario。

发布时解析出每个 agent 的生效隐私映射字段，冻结进 assistant release。runtime 与 worker 只消费冻结快照，不在运行时回查 catalog。

### 2.3 映射状态与 session 绑定，从第一版开始走 Redis

推荐实现 `SessionPrivacyMapStore` 抽象：

- 生产实现：Redis
- `local / dev` 默认也复用平台统一 Redis，确保从第一版开始就验证跨实例共享语义
- 内存版用于单元测试或显式关闭 Redis 的隔离调试场景，不作为主开发路径

不要把第一版做成仅进程内 map 再等 §3.7 重做；会话级还原能力本身就要求跨实例共享。

### 2.4 日志与审计分层

- 原值只允许存在于企业边界内的 mapper/store
- 运行日志只允许出现 placeholder、计数、策略名、阶段信息
- 审计账本继续复用 `platform_event`
- 运行态汇总统计以 Redis 中的 session summary 为实时权威，API 直接读取，不扫描事件账本现算

## 3. 控制面与发布模型

## 3.1 Catalog 数据结构

建议新增：

1. 扩展 `LlmModelConfigDto`
2. `Assistant` 级默认隐私映射模型配置
3. `AgentExecutionPolicyDto` 中新增隐私映射继承/覆盖字段

建议 LLM 资源新增字段：

- `privateDeployment: boolean`

assistant 侧建议增加：

- `privacyModelResourceId: string | null`
- `privacyMappingEnabled: boolean`

agent 侧建议增加：

- `privacyModelResourceId: string | null`
- `privacyMappingEnabled: boolean | null`

这里的职责边界必须明确：

1. assistant 侧 `privacyModelResourceId` 表示默认私有映射模型；agent 侧 `privacyModelResourceId=null` 表示继承 assistant，非空表示覆盖
2. assistant 侧 `privacyMappingEnabled` 表示默认行为开关；agent 侧同名字段 `null` 表示继承 assistant，非空表示覆盖
3. 审计不作为可配置开关，固定进入统一审计账本
4. 行为配置采用扁平字段表达，避免 assistant / agent / release / runtime 多包一层
5. 不要把模型选择和行为策略混成一个对象，否则 assistant / agent 继承关系会很快变乱

## 3.2 发布冻结

assistant release 需要补两类快照：

1. assistant 级默认 `privacyModelBinding / privacyMappingEnabled`
2. agent 级 `effectivePrivacyModelBinding / effectivePrivacyMappingEnabled`

同时 release resources 要把被引用的私有 `LLM_MODEL` 资源版本一并冻结进去，避免运行时读到漂移配置。

发布门禁建议：

1. `effectivePrivacyMappingEnabled=true` 但无有效 `effectivePrivacyModelBinding`，发布失败
2. 生效引用的 `LLM_MODEL` 若 `privateDeployment=false`，发布失败

## 4. Runtime 管道改造

## 4.1 新增统一安全管道，而不是分散塞在 prompting / tooling 里

当前 `apps/agent-runtime/agentyard_agent_runtime/` 的主模块为 `prompting.py / tooling.py / decisioning.py / openai_adapter.py / models.py`，尚无独立的数据安全模块。建议新增：

- `data_security/policy.py`
- `data_security/pipeline.py`
- `data_security/mapper.py`
- `data_security/rules.py`
- `data_security/validator.py`
- `data_security/store.py`（session map 抽象，默认走平台统一 Redis；仅测试/显式隔离调试走内存）
- `data_security/trace.py`

对外暴露一个高层接口：

1. `sanitize_outbound(session_context, channel, payload)`
2. `restore_inbound(session_context, channel, payload)`
3. `summarize_trace()`

其中 `channel` 用于区分：

- `PROMPT_INSTRUCTION`
- `PROMPT_RUNTIME_MESSAGE`
- `SKILL_PAYLOAD`
- `MODEL_TOOL_ARGUMENT`
- `TOOL_RESULT`
- `MODEL_FINAL_RESPONSE`

首版不引入 `KNOWLEDGE_RESULT` channel。`knowledge_search.query` 的 restore 走 `MODEL_TOOL_ARGUMENT`（query 本质是 tool argument 的一部分）；`knowledge_search / knowledge_read` 的返回首版不进入脱敏管道。

`PrivacyMapper` 的内部步骤建议固定为：

1. 规则预处理：先做确定性替换与归一化
2. 私有 LLM 改写：通过专门 prompt 驱动普通私有 LLM 生成占位文本
3. 一致性校验：检查同实体占位稳定、未知占位、原值泄露、restore 后残留占位等问题
4. 规则兜底/修正：对未覆盖或格式不合法的结果做最后修补

Python 侧新增环境变量：

- 复用平台统一 Redis 连接配置：`AGENTYARD_REDIS_HOST / PORT / DATABASE / USERNAME / PASSWORD / SSL_ENABLED / TIMEOUT`
- 可选新增 `AGENTYARD_PRIVACY_SESSION_STORE_KEY_PREFIX`（用于隔离 key 命名空间）
- `AGENTYARD_PRIVACY_SESSION_STORE_ENCRYPTION_KEY`（§5 加密用）

## 4.2 Owner turn 主链路

worker 通过 `SessionAgentRuntimeGateway.executeTurn` 调 `POST {agentRuntimeBaseUrl}/agent-turns/execute`（见 `apps/worker/src/main/java/com/agentyard/worker/runtime/SessionAgentRuntimeGateway.java`），契约是 `SessionContracts.AgentTurnRequest / AgentTurnResult`。当前 request 已携带 `sessionId / agentId / ...`，可直接作为 session map 的分区键，不需要额外传播通道。

owner turn 推荐改成下面的顺序：

1. worker 把冻结后的 `effectivePrivacyModelBinding / effectivePrivacyMappingEnabled` 一起放入 `AgentTurnRequest`
2. runtime 先按原值构建 `PromptBundle`
3. 在真正调用公网 LLM 前，通过 `PrivacyMapper` 对 `instruction + messages` 统一执行 `sanitize_outbound`
4. 发送脱敏后的 messages 给公网 LLM
5. 若返回 final decision，则通过 `PrivacyMapper` 对 `replyContent / accompanyingReply` 执行 `restore_inbound`
6. 若返回 `tool_calls`，则先对 arguments 执行 `restore_inbound`，再调用真实工具
7. 工具返回原值结果后，先 `sanitize_outbound`，再把脱敏后的 tool result 写回 messages，继续下一轮推理

关键点：

1. “工具参数恢复”是必须项，否则公网 LLM 生成的 `[ACCOUNT_002]` 无法被真实工具消费
2. “工具结果再脱敏”也是必须项，否则工具调用会绕过隐私边界
3. 私有映射模型本身只是普通 LLM，不负责协议一致性；一致性必须由 `PrivacyMapper` 在 runtime 内部兜住
4. `knowledge_search` 这类 builtin tool 同样要走恢复逻辑，因为查询词可能含 placeholder
5. runtime 回传 `AgentTurnResult.mappingTelemetry`（占位计数、各 channel 的 sanitize/restore 次数、未解析占位数），worker 按此写审计事件，不回传原值

## 4.3 Knowledge 链路

当前 `knowledge_search` 与 `knowledge_read` 走 `apps/agent-runtime/agentyard_agent_runtime/tooling.py` 的 `_knowledge_search / _knowledge_read`，分别对应 knowledge-service 的 `POST /internal/retrieve` 与 `POST /internal/read-chunks`。边界应定义为：

1. 发往 `/internal/retrieve` 前对 `query` 执行 `restore_inbound`（因为查询词可能来自 LLM 生成的 placeholder）
2. 发往 `/internal/read-chunks` 前对 `chunkIds` 本身不需要还原
3. knowledge-service 返回的 `hits / chunks` 首版不做 `sanitize_outbound`
4. 当前默认知识库内容无需脱敏；它继续按原值进入 LLM 上下文
5. 若后续知识库进入强合规场景，再单独扩展知识结果脱敏，不在本期预埋可配开关

## 4.4 Tool calling 链路

当前 `apps/agent-runtime/agentyard_agent_runtime/decisioning.py` 的 tool-call 主循环（`max_steps` 外层循环与单步工具分发）在同一轮里执行：

1. LLM 返回 `tool_calls`
2. runtime 解析 arguments
3. runtime 执行 HTTP/MCP tool
4. tool result 回写 messages

这里要整体改成：

1. `tool_calls.arguments` 从 placeholder 恢复为原值
2. 对恢复结果做 placeholder 合法性校验；出现未知 placeholder 直接按策略失败
3. 真正调用 HTTP/MCP provider
4. provider 返回原值结果
5. 结果脱敏后再写入 `tool_result_message`
6. runtime 日志仅记录 tool 名称、阶段、placeholder 统计，不记录原始 arguments/result

并发语义：当一轮 `tool_calls` 包含多个调用时，恢复/调用/脱敏对同一 session map 的读写必须串行或使用原子操作（Redis pipeline + Lua）。禁止在一次 turn 内产生“调用 A 已恢复但 B 尚未写回映射表”的中间状态。

## 4.5 错误与降级

失败语义统一为阻断：

1. 映射失败即终止本轮推理
2. worker 看到结构化错误，落 `AGENT_TURN_FAILED`
3. 不允许隐式或显式透传未完成 restore 的内容

## 5. Session 映射存储模型

建议 Redis 中维护三类 key：

1. `privacy:session:{sessionId}:forward`
   - `entityFingerprint -> placeholderId`
2. `privacy:session:{sessionId}:reverse`
   - `placeholderId -> encryptedRawValue + entityType + firstSeenAt + lastSeenAt`
3. `privacy:session:{sessionId}:summary`
   - 各类型计数、最近阶段、失败次数、最后活动时间

更新职责：

1. `agent-runtime` 是 session summary 的唯一写入方；每次 `sanitize / restore / blocked` 后同步更新 Redis summary
2. `api` 的 `privacy-mapping-summary` endpoint 直接读取 Redis summary，不维护第二份实时投影
3. `worker` 只负责把 `mappingTelemetry` 写入 `platform_event` 审计账本，不维护 summary 读模型

占位生成规则建议固定为：

- `[PERSON_001]`
- `[ACCOUNT_002]`
- `[ORDER_003]`

稳定性要求：

1. 同一 session 内相同实体永远得到同一 placeholder
2. 不同 session 不共享 placeholder
3. session map 生命周期跟随 session；session 终态后由统一清理流程回收，不定义独立 TTL 策略

首版就应保留 `entityType + fingerprint` 两层索引，不要只按原始字符串直接映射，否则实体归一化和类型统计都会变弱。

加密要求（对应 §11 约束 3）：

1. 原值在写入 Redis `reverse` key 前必须对称加密；禁止落明文
2. 密钥通过 `AGENTYARD_PRIVACY_SESSION_STORE_ENCRYPTION_KEY` 注入，首版走平台级单一密钥（AES-GCM 或同等强度），后续再演进到按租户 / 按 assistant 派生
3. `forward` key 存的是 `fingerprint -> placeholderId`，`fingerprint` 本身已是不可逆摘要（如 HMAC-SHA256(salt, normalized_value)），可不再二次加密
4. 日志 / 指标绝不能打印密文或密钥；指标只看 key 数量、清理状态与 session 生命周期对齐情况

## 6. `PrivacyMapper` 内部封装层

首版不引入独立的 mapper HTTP 服务协议，`PrivacyMapper` 直接作为 `agent-runtime` 内部组件存在。

建议它暴露两个方法：

1. `sanitize(content, channel, session_context)`
2. `restore(content, channel, session_context)`

内部由四个构件组成，`sanitize` 与 `restore` 两条路径按不同方式组合它们：

### 6.1 构件

1. `RuleEngine`
   - 正则、词典、格式化规则
   - 负责确定性实体识别、归一化，以及已知占位格式的直接替换
2. `PrivateLlmRewriter`
   - 使用 `privacyModelBinding` 指向的普通私有 LLM
   - 通过专门 prompt 生成带占位的文本
3. `ConsistencyValidator`
   - `sanitize` 方向：校验同实体占位稳定、是否有原值泄露
   - `restore` 方向：校验是否有未知占位、restore 后是否仍有占位残留
4. `FallbackCorrector`
   - 对 validator 未通过但可自动修复的结果做规则兜底修正

### 6.2 `sanitize` pipeline

1. `RuleEngine` 预处理：抽出确定性实体、归一化
2. `PrivateLlmRewriter` 改写：对规则未覆盖的内容走私有 LLM 生成占位版本
3. 形成本轮内存态 `candidateContent + candidateMappings`，此时禁止提前写 Redis
4. `ConsistencyValidator` 校验原值是否仍残留、同实体是否保持同 placeholder 语义
5. `FallbackCorrector` 对可修复问题做兜底修正，必要时回到 validator 再校验一轮
6. 只有最终通过校验的 `content + mappings` 才允许原子写入 session map

补充约束：

1. `sanitize` 过程中产生的映射先作为本轮 candidate 存在；禁止先写 Redis 再回滚
2. 写入 session map 时，`forward / reverse / summary` 必须一次性提交，避免半写状态
3. 若 validator 最终仍未通过，则按 §4.5 直接阻断，并丢弃本轮 candidate mappings，不污染已有 session map

### 6.3 `restore` pipeline

1. 解析输入中的 placeholder，去 session map `reverse` key 反查
2. `RuleEngine` 处理已知的格式化差异
3. `ConsistencyValidator` 校验是否仍有未知占位或未解析占位
4. `FallbackCorrector` 对可修复结果做兜底修正；仍失败则按 §4.5 直接阻断

这样 runtime 才能稳定做：

1. placeholder 校验
2. 未解析占位阻断
3. 统计与审计
4. 规则兜底与一致性修正

## 7. 审计、统计与控制台

## 7.1 审计

继续复用 `platform_event`，建议在 `PlatformAggregateType` enum（`apps/api/src/main/java/com/agentyard/platform/event/PlatformEventDtos.java`）新增 `SESSION_PRIVACY_MAPPING` 值，并同步到 TypeScript contracts 与控制台筛选文案；注意当前 `PlatformAggregateType` 是 Java enum，新增值必须随代码发布，没有动态扩展通道。

建议事件类型：

- `PRIVACY_MAPPING_CREATED`
- `PRIVACY_MAPPING_REUSED`
- `PRIVACY_OUTBOUND_SANITIZED`
- `PRIVACY_INBOUND_RESTORED`
- `PRIVACY_MAPPING_BLOCKED`

payload 只保留：

- `sessionId`
- `agentId`
- `stage`
- `entityType`
- `placeholderId`
- `privacyModelResourceId`
- `eventId`

绝不写原值。

## 7.2 汇总视图

推荐 API 新增：

- `GET /api/session-runtime/sessions/{sessionId}/privacy-mapping-summary`

读取语义：

1. 直接读取 Redis 中的 `privacy:session:{sessionId}:summary`
2. 不回退到 `platform_event` 扫描聚合
3. 若 session 已终态且 summary 已被清理，则返回“无活动映射状态”

返回：

- `enabled`
- `privacyModelName`
- `placeholderCount`
- `entityTypeBreakdown`
- `blockedEventCount`
- `lastProcessedAt`

运行页可在 session detail 中增加“隐私映射”卡片或 tab，展示：

1. 当前策略是否开启
2. 高频实体类型
3. 最近失败次数
4. placeholder 总量

首版不展示 placeholder -> 原值 反查能力。

## 8. 代码落点

### 8.1 `packages/contracts` / `packages/contracts-jvm`

需要补：

1. 扩展 `LlmModelDescriptor`（TS，`packages/contracts/src/index.ts`）与 JVM 侧对应类型
2. `SessionContracts.AgentTurnRequest` 增加 `effectivePrivacyModelBinding / effectivePrivacyMappingEnabled`
3. `SessionContracts.AgentTurnResult` 增加无原值的 `mappingTelemetry`
4. `PlatformAggregateType` enum 增加 `SESSION_PRIVACY_MAPPING`

### 8.2 `apps/api`

需要补：

1. `CatalogDtos.LlmModelConfigDto`、`AgentExecutionPolicyDto`、`AssistantDto` 扩展字段
2. `AssistantReleaseDto` 增加 assistant / agent 级 `privacyModelBinding / privacyMappingEnabled` 快照；release freeze service 写入对应字段
3. Flyway 评估：如果 `LLM_MODEL` / Assistant / Agent 的策略字段以 JSONB 存储（catalog 当前形态），则无需新增迁移；只需 service 层读写；如以独立列存储，新增 V9 迁移
4. `PlatformEventDtos` / 事件写入路径支持新 aggregate type 与事件类型
5. `SessionRuntimeController` 新增 `GET /api/session-runtime/sessions/{sessionId}/privacy-mapping-summary`，直接读取 Redis summary
6. 首版复用 assistant / agent 现有 query / save endpoint，不为“独立治理页”单独新增专门接口；若后续出现跨对象批量治理需求，再补充聚合 query / filter endpoint
7. 发布门禁校验（§3.2）与 `DeletionImpactPreview` 扩展：如私有 LLM 资源被隐私策略引用，删除预览必须显式列出依赖

### 8.3 `apps/worker`

需要补：

1. `SessionAgentRuntimeGateway.executeTurn` 请求构造处把 `effectivePrivacyModelBinding / effectivePrivacyMappingEnabled` 塞进 `AgentTurnRequest`（发布快照读取链路已存在，直接复用）
2. runtime 失败时的结构化错误透传与 `AGENT_TURN_FAILED` 写入
3. `AgentTurnResult.mappingTelemetry` 回流后写 `SESSION_PRIVACY_MAPPING` 审计事件，不再维护 summary 投影

### 8.4 `apps/agent-runtime`

需要补：

1. `agentyard_agent_runtime/data_security/*` 新模块（见 §4.1）
2. `prompting.py` 调公网 LLM 前出站脱敏
3. `openai_adapter.py` / decisioning 层 model response 入站还原
4. `decisioning.py` tool-call 主循环：arguments 恢复 + 合法性校验
5. `tooling.py` 的 `_call_http_tool / _call_mcp_tool` 前后插入 restore / sanitize 钩子；`_knowledge_search` 只做 query restore；`_knowledge_read` 不做结果脱敏
6. `models.py` 扩展 `AgentTurnRequest / AgentTurnResult`
7. 日志去原值化（现有 `logging.info` 调用点审计，确保不打印 arguments/result 原值）

### 8.5 `apps/web`

需要补：

1. assistant 页面维护 assistant 级默认隐私映射配置
2. agent 页面维护 agent 级继承/覆盖隐私映射配置
3. session runtime 页面只展示映射统计与状态，不承载配置编辑
4. 全局操作历史页补 `SESSION_PRIVACY_MAPPING` aggregate type 过滤与展示文案

### 8.6 `infra/`

需要补：

1. `infra/local/docker-compose.yml` 与 `infra/dev/docker-compose.yml` 需要提供共享 Redis service，并统一连接配置入口
2. `infra/dev` 为 Phase 2 准备可用的私有 LLM endpoint 与测试数据
3. 生产 compose / K8s manifest（§3.1）同步补 Redis 与私有 LLM 相关 Secret / TLS 配置

## 9. 分阶段实施

### Phase 0: 基础设施前置

目标：

1. 在 `infra/local` 与 `infra/dev` 的 compose 编排中引入 Redis，统一密码与连接串格式
2. API / worker / agent-runtime 引入 Redis client 依赖与启动期连通性校验
3. 与 §3.7 对齐：本次引入的 Redis 要能直接支撑后续 SSE 跨实例广播、登录态后端化等，不做只给隐私映射用的“专用 Redis”

交付判断：

- 三端都能在本地与 dev 环境连通 Redis
- 不引入与隐私映射无关的回归影响
- 为后续 Redis session map store、私有 LLM 映射封装接入、生产部署清单提供统一基础设施

### Phase 1: 规则引擎兜底 + 契约接入

目标：

1. 扩展 `LLM_MODEL` 资源配置，并把 `privacyModelResourceId / privacyMappingEnabled` 纳入 release freeze
2. runtime 接入统一安全管道
3. 用 `RuleEngine` 跑通 prompt/tool result 的脱敏
4. 打通 Redis session map store（依赖 Phase 0 基础设施）

交付判断：

- 开启策略后，公网 LLM 不再看到原值
- reply 能正确还原
- tool loop 不崩

### Phase 2: 私有 LLM 映射封装接入

目标：

1. 接入基于普通私有 LLM 的 `PrivateLlmRewriter`
2. 建立专门 prompt、规则预处理与规则兜底
3. 解决实体识别准确率、placeholder 稳定复用、未识别回退
4. 补齐 `ConsistencyValidator`

交付判断：

- 同一 session 的实体映射保持稳定
- tool arguments 的 restore 正确率可接受

### Phase 3: 审计、统计、控制台

目标：

1. 平台审计事件补齐
2. session summary endpoint 落地
3. 运行页增加隐私映射统计视图

### Phase 4: 全覆盖收口

目标：

1. 所有 resource tool 和 builtin tool 全量纳管
2. 失败策略、运维手册、性能基线补齐
3. 若业务进入强合规知识场景，再评估 knowledge 结果脱敏是否进入下一期

## 10. 测试矩阵

必须覆盖：

1. 同一 session 两次出现同一实体时 placeholder 稳定
2. 不同 session 不复用 placeholder
3. final reply 能正确还原
4. tool arguments 能从 placeholder 恢复成原值
5. tool result 会重新脱敏后再入模
6. knowledge_search 查询中的 placeholder 能恢复
7. `ConsistencyValidator` 能拦住未知占位、原值泄露、restore 后残留占位
8. `platform_event` 与 summary 只出现 placeholder 和计数，不出现原值
9. 多实例下换实例继续还原成功

## 11. 设计约束

本方案的实现边界如下：

1. 不新增资源类型；直接扩展现有 `LLM_MODEL` 资源，并增加 `privateDeployment` 配置。
2. 不引入新的 domain/scenario 级继承链；直接复用现有 assistant 默认 + agent override 的 LLM 继承模式。
3. 映射表原值必须加密后写入 Redis（首版平台级单一密钥，AES-GCM；密钥通过环境变量注入）。
4. UI 首版复用 Assistant / Agent 页面承载配置编辑；运行页只展示统计与状态。若后续出现跨对象盘点、批量治理或专门治理角色需求，再升级为独立治理页。
5. 映射失败统一阻断，不提供 `PASSTHROUGH` 透传策略。
6. 本方案引入的 Redis 与 §3.7 共用同一套基础设施，不单独部署仅供隐私映射使用的 Redis 实例。
7. 私有映射模型资源若被任何已发布 assistant 的隐私策略引用，删除操作必须经过 `DeletionImpactPreview` 校验，与现有资源删除防护一致。
8. 私有映射模型本身是普通 LLM；真正的映射能力由 `agent-runtime` 内部 `PrivacyMapper` 封装层承担，不额外引入专用 mapper 资源类型或专用 mapper 协议。
9. 当前知识库内容默认无需脱敏；首版知识链路只做 query restore，不做结果 sanitize。
