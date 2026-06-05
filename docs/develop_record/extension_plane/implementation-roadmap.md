# Extension Plane Implementation Plan

> 本文是 Extension Plane 编码前的唯一实施入口，同时维护任务拆分、影响范围和已定实施约束。

## Implementation Slices

本节定义可执行依赖顺序。后续章节是影响面 checklist，不能替代本节顺序；并行实施时也必须满足每个 slice 的依赖和验收条件。

### Slice 1: Protocol assets + canonical JSON

依赖：无。

产物：

1. `packages/extension-protocol`。
2. extension 边界协议的 OpenAPI / JSON Schema 事实源；Web-facing API 与 core -> core internal API 仍归 `packages/contracts/openapi/*`。
3. service-level manifest envelope、channel provider descriptor、tool connector descriptor、credential lifecycle DTO、`ExtensionError`、auth / trace header 契约。
4. `AgentYardCanonicalJson` helper 设计：基于 RFC 8785/JCS 的确定性排序和字符串规则，但面向 descriptor / registration digest 场景收窄输入 profile；不引入第三方 JCS 库作为信任根。
5. protocol self-check 命令，校验 schema、examples、fixtures、digest 和 cross-schema consistency。
6. UI schema sibling 字段、JSON Pointer 引用规则、静态 options 校验和 UI / JSON Schema 冲突检测 fixtures。
7. Web schema-driven form 的第一版实现边界文档：Ant Design Vue 薄渲染层 + Ajv 2020 validation，不引入重型 form generator。

验收：

1. protocol self-check 可在 CI 入口运行。
2. duplicate key、safe integer 边界、float/decimal/exponent 拒绝、Unicode / escape、非 ASCII key 排序、大整数字符串化、default port、host lowercase、trailing slash 等 fixture 被覆盖。
3. config schema + UI schema、credential secret UI schema、schedule job config UI schema、operation mapping UI schema、conditional required JSON Schema 的 valid / invalid fixtures 被覆盖。
4. invalid fixtures 覆盖 UI field 找不到 JSON Schema property、普通 config 使用 secret、UI required 试图替代 JSON Schema required、UI hidden 但 JSON Schema 无条件 required、options 与 enum 漂移。
5. descriptor-level request envelope 的 fixtures 覆盖 `traceContext.traceparent` 必填；service-level manifest / health endpoint fixtures 覆盖不要求 trace header。
6. 生成代码不提交仓库；`packages/extension-protocol` 内的 OpenAPI / JSON Schema 是 extension 边界协议的唯一事实源。

禁止提前做：

1. 不实现 Web 表单。
2. 不实现 remote runtime adapter。
3. 不提交 generated SDK DTO / client / server stub。

### Slice 2: JVM / Python SDK skeleton + contract tests

依赖：Slice 1。

产物：

1. `packages/extension-sdk-jvm` 接入 Gradle build。
2. `packages/extension-sdk-python` 接入 uv workspace。
3. Java SDK 使用 OpenAPI Generator 生成 extension 边界协议 DTO / enum / helper model 到 Gradle build 目录。
4. Python SDK 使用 `datamodel-code-generator` 生成 Pydantic v2 model 到 Python build 目录。
5. Java / Python SDK 提供协议 DTO / 类型 facade、协议常量、manifest validator、canonical JSON、`ExtensionError` 解析、HTTP client / server helper 契约。
6. Java SDK、Python SDK 与 `agent-runtime` 复用 Python SDK 的跨语言 fixtures。

验收：

1. Java SDK contract tests 与 Python SDK contract tests 使用同一份 protocol fixtures。
2. `agent-runtime` 不再维护第二套 manifest envelope / canonical JSON / `ExtensionError` 解析实现。
3. SDK 如采用手写 facade，必须被 OpenAPI / JSON Schema contract tests 完整约束。
4. JSON Schema validation 由运行时 validator 执行，不通过生成代码实现。
5. canonical JSON、duplicate key 检测、AgentYard canonical JSON profile 输入约束和 digest 计算由 SDK hand-written helper + fixtures 约束，不由 generator 生成，也不引入第三方 JCS 库作为信任根。

禁止提前做：

1. 不把手写 SDK DTO 变成协议事实源。
2. 不在 `agent-runtime` 内复制 SDK 已提供的协议层能力。

### Slice 3: Static registration loader + core preset merge

依赖：Slice 1、Slice 2。

产物：

1. operator yaml registration loader。
2. `core-channel-gateway` / `core-agent-runtime` preset 注入。
3. `baseUrl` env placeholder 解析与 normalization。
4. `registrationConfigDigest` 计算。
5. reserved `core-` 前缀、operator preset overwrite、auth type、descriptor expose 白名单的配置校验。

验收：

1. API、`agent-runtime`、`channel-gateway` 使用同一 loader 行为和 digest 输入。
2. token 值不进入 registration config digest。
3. operator yaml 不允许声明或重写 core preset。

禁止提前做：

1. 不实现 API aggregate registry UI。
2. 不让 runtime 使用独立 URL 列表或第二套 registration 配置。

### Slice 4: Runtime manifest endpoints + internal DescriptorProvider

依赖：Slice 1、Slice 2、Slice 3。

产物：

1. `channel-gateway` 内部 `DescriptorProvider`。
2. `agent-runtime` 内部 `DescriptorProvider`。
3. 两个 runtime 都暴露 `GET /extension/manifest`，HTTP envelope 与内部 provider 输出 byte-for-byte 一致。
4. runtime owner registry validation endpoint。

验收：

1. `channel-gateway` / `agent-runtime` 各跑两组 contract tests：HTTP path 与内部 `DescriptorProvider` path。
2. core preset 自加载使用内部 provider，不调用自身 HTTP。
3. runtime owner validation 能返回 registration digest、descriptor ids、definition digests 和错误项。

禁止提前做：

1. 不实现 Web definition 页面。
2. 不实现完整 remote invocation；只完成 manifest / registry 可验证闭环。

### Slice 5: API registry aggregation + definition endpoints

依赖：Slice 3、Slice 4。

产物：

1. API 聚合 operator registration + core preset manifest。
2. API aggregate validation endpoint。
3. `/api/extensions/channel-providers` definition endpoint。
4. `/api/extensions/tool-connectors` definition endpoint。
5. `ChannelProviderDefinition` / `ToolConnectorDefinition` control-plane projection DTO，事实源落在 `packages/contracts/openapi/control-plane.yaml`。
6. API 与 runtime owner validation 的 digest consistency 校验。

验收：

1. definition endpoint 只暴露 schema、UI schema、metadata、credential capability，以及 channel profile / provider job 的非敏感 default；不暴露 extension `baseUrl`、endpoint path、internal token、`externalSecretRef`、credential 明文或 runtime-only registry state。
2. `credentialCapability.supported = true` 只表示 Core / Web 可以管理 credential，不表示 runtime 必需 credential。
3. Definition DTO 不提供 `accountRequirement = REQUIRED / OPTIONAL / NONE`；Integration Account 仍是可选引用，缺少 credential 是否可运行由 extension runtime 决定。
4. API 必须最后更新并最后执行 aggregate validation 的部署顺序被 contract / smoke check 覆盖。
5. Web 后续只依赖 definition endpoint，不读取静态 connector / provider 定义。

禁止提前做：

1. 不在 Web 内写 provider / connector 常量。
2. 不让 API 绕过 runtime owner validation 直接假定 runtime registry 已通过校验。

### Slice 6: Integration Account target model + optional credential lifecycle

依赖：Slice 5。

产物：

1. API-owned `integration_account` target model。
2. account status、credential status，以及沿用现有实现的 `credential_ciphertext` / `credential_fingerprint`；第一版不引入 credential metadata。
3. 可选 credential create / rotate / revoke / validate API；create 支持 Web-facing 一步创建 Integration Account 并提交 initial credential。
4. descriptor 声明 credential endpoints 时走 remote credential lifecycle；core reference descriptor 使用 Core-owned encrypted reference secret 时走 API 本地加密存储；其他 descriptor 未声明 credential endpoints 时不暴露 credential lifecycle action。
5. Integration Account create / update / list / get / archive / status API。
6. Integration Account create / update 使用当前 definition registry 的 `accountConfigSchema` 校验
   `config`，校验 owner 是 API。
7. Integration Account `name` 创建必填，作为唯一账号展示名；credential lifecycle response 不回填或覆盖 `name`。
8. Account 可用性判定规则，供后续 Tool publish 和 Channel Profile 写入复用：`subjectType + subjectId` 必须匹配目标 descriptor，`status` 必须为 `ENABLED`，`credential_status = REVOKE_FAILED / REVOKED` 是硬阻断，其他 credential status 只做风险提示。

验收：

1. credential endpoints 可选语义、固定常量密钥语义、account 级短时排他、无 `Idempotency-Key` / `idempotencyKey`、无 `X-AgentYard-Extension-*` header、无 logical operation 幂等恢复、无 token refresh / status sync 被 contract tests 覆盖。
2. Integration Account create / update contract tests 覆盖 `subjectType + subjectId` descriptor 查找、`accountConfigSchema` 成功 / 失败、descriptor 不存在和 registry not ready 错误映射；create with credential 覆盖先生成 account id，再按 descriptor 模式调用 extension `createCredential` 或写入 Core-owned encrypted reference secret。
3. Core-owned encrypted reference secret tests 覆盖 credential 加密存储、fingerprint 生成、internal runtime resolver 解密返回、Web/API read DTO 不回显密文 / 明文、remote extension 无法调用 resolver。
4. Integration Account tests 覆盖创建时 `name` 必填、credential lifecycle response 不定义 metadata、不回填 / 覆盖 `name`，且额外 metadata 字段被忽略、不保存、不展示。
5. Account 可用性 contract tests 覆盖 subject 不匹配、`status != ENABLED`、`REVOKE_FAILED` / `REVOKED` 硬阻断，以及 `NOT_CONFIGURED` / `VALIDATION_FAILED` / `ROTATION_REQUIRED` 只作为风险提示。
6. Slice 6 不写 Tool release snapshot，也不写 channel runtime profile；只提供后续 materialization 需要的 account 数据模型、credential lifecycle 和可用性判定。

禁止提前做：

1. 不做 provider job credential refresh。
2. 不让 channel-gateway / agent-runtime 发起 credential lifecycle invocation。
3. 不在 Slice 6 修改 Tool Resource -> assistant release snapshot materialization。
4. 不在 Slice 6 修改 Channel Profile -> channel runtime profile materialization。

### Slice 7: Tool connector manifest migration + remote invocation adapter

依赖：Slice 5、Slice 6。

产物：

1. 内置 `simple-http`、`business-code-secret-http`、`mcp` 转成 manifest 驱动。
2. 删除 API / Web / Runtime 三处重复 connector 定义。
3. `ToolConnectorRegistry`。
4. remote connector invocation adapter。
5. Tool Resource 保存 `connectorType` string、可选 `accountId`、connector config、operation mapping 和 retry policy。
6. Tool Resource -> assistant release snapshot materialization：API publish 时从 Tool Resource / Tool Operation 冻结 `connectorType`、可选 `accountSnapshot.accountId`、可选当前 `externalSecretRef`、connector config、operation mapping 和 retry policy。
7. operation mapping schema-driven UI 的 API 契约准备。

验收：

1. remote tool invoke contract tests 覆盖 auth header、descriptor context、`traceContext.traceparent`、idempotency、timeout / retry / circuit breaker、output schema 校验。
2. runtime output 必须是 JSON object。
3. connector definition endpoint 是 Web 的唯一 connector definition 来源。
4. `agent-runtime` 构造 `RemoteToolInvokeRequest` 时只读取 release snapshot，不回 API 读取 account 或 credential。
5. API publish 复用 Slice 6 的 account 可用性判定；`REVOKE_FAILED` / `REVOKED` 不允许生成新的 release snapshot，缺少 `externalSecretRef` 不阻塞发布。

禁止提前做：

1. 不在 Web 内恢复 connector 静态定义。
2. 不在 runtime adapter 中绕过 SDK HTTP / error / canonical JSON helper。

### Slice 8: Channel provider registry + profile rename + gateway internal admin

依赖：Slice 5、Slice 6。

产物：

1. `ChannelProviderType` enum 重构为 string descriptor id。
2. `channel_account` 直接重命名为 `channel_profile`。
3. `ChannelProviderRegistry`。
4. Feishu gateway-native reference provider。
5. `packages/contracts/openapi/channel-gateway-internal.yaml` 覆盖 channel admin internal API。
6. `packages/contracts/openapi/control-plane.yaml` 覆盖 Web-facing `/api/channel-admin/*` profile / template binding / provider job / manual run API。
7. API channel admin 只保留 Web-facing DTO、鉴权、路由和 `ChannelGatewayClient`。
8. Channel Profile -> channel runtime profile materialization：API 调 `channel-gateway` internal profile create / update 前物化可选 `accountSnapshot.accountId` 与可选 `accountSnapshot.externalSecretRef`。

Rename checklist：

1. DDL：`channel_account` -> `channel_profile`
2. DDL：`channel_conversation_binding.channel_account_id` -> `channel_profile_id`
3. DDL：`channel_inbound_event.channel_account_id` -> `channel_profile_id`
4. DDL：`channel_outbound_delivery.channel_account_id` -> `channel_profile_id`
5. DDL index / unique names 同步改为 profile 语义：
   `idx_channel_profile_updated`、`idx_channel_binding_profile_updated`、
   `idx_channel_inbound_profile_created`、`idx_channel_outbound_profile_created`、
   `uk_channel_conversation_binding_profile_external_conversation`
6. 新增 / 明确 `channel_profile_template_binding.channel_profile_id`、
   `channel_profile_job.channel_profile_id` 和 `channel_profile_job_run.job_id`
7. jOOQ generated schema 清理旧 `ChannelAccount*` / `CHANNEL_ACCOUNT_ID` 产物后重新生成；
   目标产物必须是 `ChannelProfile*` / `CHANNEL_PROFILE_ID`
8. JVM contract DTO：`ChannelAccount*` -> `ChannelProfile*`，
   `CreateChannelAccountPayload` / `UpdateChannelAccountPayload` -> `CreateChannelProfilePayload` /
   `UpdateChannelProfilePayload`
9. API / Web-facing path：`/api/channel-admin/accounts*` -> `/api/channel-admin/profiles*`
10. channel-gateway internal path：`/internal/channel-admin/accounts*` ->
    `/internal/channel-admin/profiles*`
11. Java service / repository / controller / client 方法名：`Account` 语义改为 `Profile`，
    path variable `accountId` 改为 `channelProfileId`
12. Web service / type / page / test 命名：`ChannelAccount` 语义改为 `ChannelProfile`，
    前端只调用 `/channel-admin/profiles`
13. gateway-native provider 代码：例如 Feishu `findAccountByProviderAppId` 改为
    `findProfileByProviderAppId` 或等价 profile 语义
14. error message / audit event / metrics label 中面向 channel runtime profile 的文案改为
    `channel profile`，不再出现 `channel account`
15. tests 必须覆盖旧 `/accounts` path 不再存在；目标实现不保留 redirect、alias 或兼容 DTO

验收：

1. `packages/persistence-jvm` 不承载 channel runtime generated schema、repository 或 business store API。
2. API 不直连 channel runtime 表。
3. channel admin API 调 gateway 前物化可选 `accountSnapshot.accountId` 与 `accountSnapshot.externalSecretRef`，Web-facing DTO 不返回 `externalSecretRef`。
4. Web-facing DTO 返回 Integration Account 展示状态、`credentialStatus` 风险提示和 `hasExternalSecretRef`，但不返回 secret material。
5. Channel Profile create / update 复用 Slice 6 的 account 可用性判定；`REVOKE_FAILED` / `REVOKED` 不允许生成新的 channel runtime profile，缺少 `externalSecretRef` 不阻塞保存 / 启用。
6. `channel-gateway` 只读 channel runtime profile，不回 API 拉取 credential 数据或账号状态。
7. `rg \"ChannelAccount|channel_account|channel_account_id|/channel-admin/accounts\" apps packages docs/todo/extension_plane`
   只允许命中历史迁移说明或本 rename checklist；目标源码、OpenAPI、generated jOOQ 和测试不得残留旧命名。

禁止提前做：

1. 不在 API 里保留 channel runtime 表读写捷径。
2. 不为旧 enum 或旧表名保留兼容层。

### Slice 9: Normalized event + provider job + outbound/template binding

依赖：Slice 8。

产物：

1. `POST /internal/channel-events/normalized`。
2. inbound normalized event ingest / dedup / binding。
3. provider job scanner、Redis lock、job run history。
4. outbound delivery failure state。
5. external template binding resolver。
6. gateway-native outbound conversion adapter 与 remote provider outbound request。

验收：

1. normalized event contract tests 覆盖 `eventType` 字段矩阵。
2. provider job 不包含 credential refresh / rotate / status sync。
3. outbound delivery 不做 scheduled retry。
4. remote provider request 只传 canonical message block 和 resolved external template ref，不解释 provider-native template body。
5. remote provider inbound 第一版不增加 Core -> provider mapping API，不保存 `inboundVerificationStatus`，启用 inbound 不阻塞；验证闭环由 runbook / smoke check 的 test normalized event 覆盖。

禁止提前做：

1. 不把 provider-native payload 写入 assistant message block。
2. 不把 external template 当成 assistant message 字段。
3. 不做 Core -> provider mapping API 或 mapping status API。

### Slice 10: Web schema-driven pages

依赖：Slice 5、Slice 6、Slice 7、Slice 8。

产物：

1. provider definition driven forms。
2. tool connector definition driven forms。
3. integration account management pages。
4. credential form、config form、job form、operation mapping form。
5. Channel profile 配置页。
6. `SchemaDrivenForm` 薄渲染层，使用 Ant Design Vue 控件和 Ajv 2020 validation。

验收：

1. Web 不维护 provider / connector 静态定义。
2. Web 普通配置表单不允许 secret 控件、credential 明文、`externalSecretRef` 或可还原 secret material。
3. 配置页必须用 account status 阻止保存 / 发布 / 启用；credential status 中 `REVOKE_FAILED` / `REVOKED` 是硬阻断，`NOT_CONFIGURED` / `VALIDATION_FAILED` / `ROTATION_REQUIRED` 只做风险提示。
4. Web renderer tests 覆盖 JSON Pointer 字段映射、visibilityCondition 显隐、Ajv validation、API validation error 映射和 secret 控件不回显。

禁止提前做：

1. 不绕过 definition endpoint 直接读取 manifest。
2. 不在前端拼接 provider-specific secret routing。

### Slice 11: Enterprise sample repo + deploy smoke check

依赖：Slice 4、Slice 5、Slice 7、Slice 8、Slice 9。

产物：

1. private-style channel provider sample。
2. private-style tool connector sample。
3. extension-managed secret 示例。
4. Dockerfile、compose overlay、Helm overlay 和 contract tests。
5. deployment smoke check script / runbook。
6. 投产 runbook 和安全检查项。

验收：

1. sample overlay 演示同一个 internal token Secret 投影到 core service 与 enterprise extension service。
2. smoke check 覆盖 runtime owner validation、API aggregate validation、core preset service `/health/ready`、enterprise extension `/extension/health` 和 enterprise extension `/health/ready`。
3. smoke check 覆盖 enterprise extension 缺少 `AGENTYARD_INTERNAL_TOKEN_FILE` 或 token Secret 未挂载时的 auth failure 样例。

禁止提前做：

1. 不把 sample repo 私有依赖纳入 core 权威状态模型。
2. 不把 token 写进 registration yaml、Web、release snapshot、session、Temporal history 或普通日志。

## 1. Protocol 与 Registry

1. 新增 `packages/extension-protocol`
2. 新增 `packages/extension-sdk-jvm`
3. 新增 `packages/extension-sdk-python`
4. 定义 channel provider manifest schema
5. 定义 tool connector manifest schema
6. 定义 service-level manifest envelope 与 `GET /extension/manifest`
7. 定义可选固定常量密钥的 credential create / rotate / revoke / validate protocol；descriptor 可以不暴露 credential endpoints，由 extension 私有配置 / 私有存储维护 credential；credential lifecycle 不使用 `Idempotency-Key` / `idempotencyKey`，不引入 token refresh endpoint、provider-driven rotation 或后台 status sync
8. 定义统一 error model、auth header、trace header
9. 定义 `AgentYardCanonicalJson` 序列化 helper、输入 profile、`sha256:<lowercase-hex>` digest 规则和跨语言 fixtures
10. 定义并测试 `baseUrl` URL 规范化：允许 path prefix，host 小写，默认端口归一，移除 trailing slash，禁止 userinfo / query / fragment；normalized `baseUrl` 同时用于 `registrationConfigDigest` 和运行时 HTTP 调用
11. 基于 OpenAPI / JSON Schema 生成或约束 Java SDK 的 DTO、协议常量、manifest 校验入口、canonical JSON 实现和 HTTP client / server 辅助契约
12. 基于 OpenAPI / JSON Schema 生成或约束 Python SDK 的 DTO / 类型、协议常量、manifest 校验入口、canonical JSON 实现、HTTP client / server 辅助契约和 pytest contract helpers
13. `apps/agent-runtime` 复用 `packages/extension-sdk-python` 的 manifest envelope 序列化、JSON Schema 校验、`ExtensionError` 解析和 canonical JSON 实现，跑跨语言 fixtures
14. 定义静态注册配置结构（operator yaml + core preset 合并模型）
15. 实现 core preset registration 注入：`core-channel-gateway` / `core-agent-runtime`，由 env 提供 `baseUrl`，operator yaml 不允许重写
16. 在 `channel-gateway` / `agent-runtime` 内抽 `DescriptorProvider`，对外通过 `/extension/manifest` 暴露，对内被本服务 registry 直接读
17. `channel-gateway` 暴露 `GET /extension/manifest`，envelope shape 与 enterprise extension 一致
18. `agent-runtime` 暴露 `GET /extension/manifest`，envelope shape 与 enterprise extension 一致
19. 定义 `ToolConnectorRegistry` 与 `ChannelProviderRegistry` 的分域加载规则
20. 定义 extension service `registrationId` 与 manifest descriptor 的校验规则
21. 定义 API definition registry 与 runtime registry 的 descriptor id / definition digest 一致性校验，覆盖 registration config 变更部署顺序（runtime owner 先，API 最后）
22. 定义 `extensionApiVersion` major upgrade 的 blue / green stack 切换流程，由 API 入口切流，不做同一 stack 内原地滚动升级
22. 补 protocol self-check、Java SDK contract tests、Python SDK contract tests 和 extension implementation contract tests 骨架；`channel-gateway` / `agent-runtime` 跑两次 contract tests（HTTP 路径 + 内部 `DescriptorProvider` 路径）

当前不创建 `extension-sdk-ts` 或其他语言 SDK。`agent-runtime` 的 Python 协议能力必须来自 `packages/extension-sdk-python`，不作为 runtime 内部私有实现单独维护。

构建集成约束：

1. `packages/extension-protocol`、`packages/extension-sdk-jvm`、`packages/extension-sdk-python` 必须接入现有构建入口，不能只落目录不参与 CI
2. `packages/extension-sdk-jvm` 必须进入 Gradle build，随 `./gradlew check` 运行 Java SDK contract tests
3. `packages/extension-sdk-python` 必须进入 uv workspace，随 `uv run pytest` 或对应 Python CI 运行 Python SDK contract tests
4. `packages/extension-protocol` 必须提供 protocol self-check 命令，验证 extension 边界协议的 OpenAPI、JSON Schema、examples、fixtures 和 cross-schema consistency
5. Java 生成工具固定为 OpenAPI Generator（Gradle plugin / CLI），输出到 `packages/extension-sdk-jvm/build/generated/*` 或等价 Gradle build 目录
6. Python 生成工具固定为 `datamodel-code-generator`，输出到 `packages/extension-sdk-python/build/generated/*` 或等价 Python build 目录；当前不使用 `openapi-python-client` 生成完整 client package
7. `packages/extension-protocol` 内的 OpenAPI / JSON Schema 是 extension 边界协议的唯一提交事实源；由它们生成的 SDK DTO、client、server stub、validator glue 等产物不提交仓库，只在 build / test 阶段生成到构建目录
8. 如果 SDK 采用手写 DTO / helper，则必须被 OpenAPI / JSON Schema contract tests 完整约束；手写实现不得成为第二套协议事实源
9. CI 门禁以 protocol self-check + Java SDK contract tests + Python SDK contract tests + runtime SDK 复用测试为准，不以提交 generated source 并比较 diff 为准

## 2. Channel Provider

1. 将 `ChannelProviderType` enum 重构为 string descriptor id
2. 将 Channel Profile 与 Integration Account 收敛为统一账号 / 凭证模型
3. 将 `channel_account` 直接重命名为 `channel_profile`
4. 将 Feishu 抽成 gateway-native reference provider
5. 引入 `channel_profile_job` 和 `channel_profile_job_run`
6. 实现 `channel-gateway` provider job scanner：`@Scheduled` 扫描 due job，Redis lock 做多实例去重，持久化 `RUNNING` 状态，并在 lock 缺失且 run 超过 `job_timeout_seconds` 快照时恢复 stale `RUNNING`；provider job 不包含 credential refresh / rotate / status sync
7. outbound delivery 不做定时任务重试；失败只记录 delivery 状态和错误信息
8. 明确 outbound 渲染 / 转换 owner：gateway-native / 内置 provider 由 `channel-gateway` 负责，remote provider 由 extension 自己负责
9. 实现 provider `runJob` execution 与结果持久化
10. 补齐 inbound normalized event internal API
11. normalized event DTO 使用 `channelProfileId`
12. normalized event internal API 补 registrationId / descriptor / internal token 鉴权校验

## 3. Tool Connector

1. 将内置 `simple-http`、`business-code-secret-http`、`mcp` 转成 manifest 驱动
2. 删除 API / Web / Runtime 三处重复 connector 定义
3. 增加 remote connector invocation adapter
4. tool connector manifest 补可选 credentialSchema / credential endpoints / endpoint path 规则；enterprise remote descriptor 未声明 credential endpoints 时不提供 Core/Web credential lifecycle，core reference descriptor 可用 Core-owned encrypted reference secret；声明任一 credential endpoint 时必须完整声明四个 endpoint 和 schema；manifest validation 只做轻量 shape 校验，credential 缺失或不可用允许在 runtime invocation 时失败
5. 补 connector manifest contract tests
6. 补 operation mapping schema-driven UI

## 4. Enterprise Extension Templates

1. 新建 private-style channel provider sample
2. 新建 private-style tool connector sample
3. 提供 extension-managed secret 示例
4. 提供私有 DB / Vault / KMS 接入模板
5. 提供 Dockerfile、compose overlay 和 contract test
6. 文档化企业 repo 推荐结构
7. 文档化投产 runbook 和安全检查项

## 5. Governance

1. extension 健康检查聚合只用于部署后 smoke check、状态展示和运维诊断，不参与 Core registry 启动门禁；core preset 不跑 `/extension/health`，只跑对应 core service 自身 `/health/ready` 与 registry validation
2. extension invocation metrics
3. extension 审计事件
4. credential create / rotation / revoke 审计事件
5. extension timeout / retry / circuit breaker
6. dynamic registration 不在当前目标范围内

## 6. Impact Checklist

Extension Plane 改造已经不只是 `ChannelProviderType` enum -> string。当前实施同时引入：

1. Channel Provider Registry
2. Tool Connector Registry
3. 可选统一 Integration Account
4. 可选 `externalSecretRef`
5. remote tool invocation
6. gateway-native / remote channel provider
7. provider job scanner

编码前必须把本节作为强制 impact checklist，覆盖整个 extension plane 的迁移影响面。

### 6.1 Contracts / OpenAPI / SDK

1. `packages/contracts-jvm/src/main/java/com/agentyard/contracts/channel/ChannelContracts.java`
2. `packages/contracts/src/index.ts`
3. `packages/contracts/openapi/control-plane.yaml`
4. `packages/extension-protocol`
5. `packages/extension-sdk-jvm`
6. `packages/extension-sdk-python`
7. Channel DTO：`ChannelProviderType enum -> string providerType`
8. Channel runtime profile DTO
9. Integration Account DTO：`subjectType / subjectId / optional externalSecretRef / credentialStatus`
10. Tool connector definition DTO
11. Extension definition DTO
12. control-plane definition endpoint schema：`ApiResponseChannelProviderDefinitionList` / `ChannelProviderDefinition` / `ApiResponseToolConnectorDefinitionList` / `ToolConnectorDefinition`；Definition DTO 是 descriptor projection，不是 manifest 原样透传
13. Java SDK DTO / constants / validation helper 从 `packages/extension-protocol` OpenAPI / JSON Schema 在构建目录生成，或由手写 facade 加 contract tests 约束；生成产物不提交
14. Python SDK DTO / constants / validation helper 从 `packages/extension-protocol` OpenAPI / JSON Schema 在构建目录生成，或由手写 facade 加 contract tests 约束；生成产物不提交
15. Tool Connector type string descriptor 化

### 6.2 Database / Persistence

1. API-owned migration 只放 control-plane tables：`apps/api/src/main/resources/db/migration/*`
2. Channel runtime migration 只放 `channel-gateway`：`apps/channel-gateway/src/main/resources/db/migration/*`
3. `channel-gateway` 默认使用独立 PostgreSQL database / schema 边界，当前 datasource 指向 `agentyard_channel_gateway`，不与 API / worker 的 `agentyard_core` 共享 `public` schema
4. `channel-gateway` Flyway history table 使用 `channel_gateway_schema_history`，不复用 API 的 `flyway_schema_history`
5. API / worker 公共 jOOQ generated schema 由 `packages/persistence-jvm/src/codegen/java/com/agentyard/persistence/codegen/JooqCodegenMain.java` 生成，只读取 API migration root
6. `channel-gateway` 自己维护 jOOQ codegen 和 generated schema，生成包为 `com.agentyard.channel.gateway.jooq`，只读取 `apps/channel-gateway/src/main/resources/db/migration`
7. `packages/persistence-jvm` 只作为 API / worker 的公共 persistence 模块，不承载 channel runtime generated schema、repository 或 business store API
8. Channel runtime repository / store 归属 `apps/channel-gateway/src/main/java/com/agentyard/channel/gateway/channel/*`
9. API channel admin 不直连 channel runtime 表，只通过 `ChannelGatewayClient` 调 `channel-gateway` internal API
10. API-owned：integration account 相关 repository / store
11. API-owned：`integration_account` 增加 `subject_type`
12. API-owned：`integration_account` 增加 `subject_id`
13. API-owned：`integration_account` 增加 `external_secret_ref`
14. API-owned：保留 / 收敛现有 `integration_account.credential_ciphertext` 与 `credential_fingerprint`，作为 Core-owned encrypted reference secret 的存储字段
15. API-owned：`integration_account` 增加 `credential_status`
16. channel-gateway-owned：`channel_account` 直接重命名为 `channel_profile`
17. channel-gateway-owned：`channel_profile` 保存 `integration_account.id` 引用值；是否可运行由 API / channel admin 链路在写入前校验
18. channel-gateway-owned：`channel_profile` 不承载明文 credential；可保存启用 / 更新时已有的 `externalSecretRef`，但该字段不是所有 provider 必填
19. channel-gateway-owned：`channel_account_job` 直接重命名为 `channel_profile_job`，并增加 `cursor`、`next_run_at`、`failure_count`、`last_error`、`last_run_id`、`last_run_at` 等调度字段；`status` 包含 `ACTIVE / RUNNING / PAUSED / DISABLED`，并作为 provider job 运行态事实源；write DTO 中的 `scheduleConfig.enabled` 保存时投影成 `ACTIVE / DISABLED`，不进入持久化 `schedule_config`
20. channel-gateway-owned：新增 `channel_profile_job_run` 保存 provider job run history，run `status` 包含 `RUNNING / SUCCEEDED / FAILED / TIMED_OUT`，并快照本次执行的 `job_timeout_seconds`
21. channel-gateway-owned：provider type 字段继续按 string 存储，但 Java 映射不再 `Enum.valueOf`
22. channel-gateway-owned：`channel_profile.integration_account_id` 允许 nullable（channel provider 不默认必绑 Integration Account）
23. channel-gateway-owned：`channel_outbound_delivery` 增加或明确 `idempotency_key`；失败只记录 `status` / `last_error` / `attempt_count`，不保存本地重试计划
24. channel-gateway-owned：`channel_profile` 增加或明确 `inbound_enabled` / `assistant_binding`，其中 provider-specific `config` 由 `configSchema` 校验，`assistant_binding` 由平台固定 schema 校验；不引入 `outbound_enabled`，因为 `sendOutbound` 是所有 channel provider 的固定能力，整体停用由 profile `status` 表达，单个 outbound 映射停用由 template binding `enabled` 表达
25. channel-gateway-owned：新增 `channel_profile_template_binding`，按 `assistant_id + channel_profile_id + message_type + message_subtype + message_version` 维护 external template ID 映射

### 6.3 Channel Gateway

1. `apps/channel-gateway/src/main/java/com/agentyard/channel/gateway/channel/*`
2. `apps/channel-gateway/src/main/java/com/agentyard/channel/gateway/connector/feishu/*`
3. channel-gateway tests
4. `ChannelProviderRegistry`
5. gateway-native provider adapter interface
6. Feishu provider 改为 reference provider
7. `DescriptorProvider` 内部抽象，聚合 gateway-native adapter
8. `GET /extension/manifest` HTTP endpoint，由 `DescriptorProvider` 驱动
9. `POST /internal/channel-events/normalized`
10. normalized event ingestion / dedup / binding
11. normalized event internal auth / registrationId / descriptor 校验
12. normalized event DTO 使用 `channelProfileId`
13. outbound delivery 调 provider `sendOutbound`
14. provider `runJob` execution
15. `channel-gateway` provider job scanner：`@Scheduled` + Redis lock + 持久 `RUNNING` claim + stale `RUNNING` recovery；scanner 和 manual run 只依据 `channel_profile_job.status` 判断启用 / 禁用，不读取 `schedule_config.enabled`
16. outbound delivery 不做定时任务重试；失败落 `FAILED` 并写错误日志，无 `NEEDS_ATTENTION` 自动升级
17. registry descriptor id / definition digest consistency startup validation
18. `core-channel-gateway` preset registration 自加载路径（直接读 `DescriptorProvider`，不调自身 HTTP）
19. `channel-gateway` registry 只加载 `exposes.channelProviderTypes` 非空的 registration；tool-only registration 不进入 manifest fetch、startup gate、runtime validation 或 descriptor digest comparison
20. `channel-gateway` active-active runtime topology：Postgres state authority、Redis provider job lock、bounded graceful shutdown
21. external template binding resolver：从 session assistantId、channelProfileId、SessionMessageBlock type / cardType / version 解析 external template ref，并写入 delivery snapshot
22. gateway-native outbound conversion adapter；remote provider request 只透传 canonical message block 和 resolved external template ref，不解释 provider-native template body
23. normalized event ingest 只使用 `channel_profile.assistant_binding` 保存态快照做路由，不直读 API-owned assistant / scenario 状态，也不在 ingest 前回 API 实时校验；后续对话或 control-plane 操作遇到失效 assistant / scenario 时由 API 返回结构化业务错误
24. `packages/contracts/openapi/channel-gateway-internal.yaml` 覆盖 `/internal/channel-admin/*`：profile CRUD、template binding CRUD、provider job config CRUD、manual job run 和统一 error mapping

### 6.4 API

1. API 层 channel admin 仅保留 Web-facing DTO、鉴权、路由和 `channel-gateway` internal client；channel runtime 表读写归 `channel-gateway`
2. API tests around channel admin
3. authorization tests that reference channel admin DTOs
4. integration account API / DTO
5. Tool connector account binding normalization
6. `/api/extensions/channel-providers` definition endpoint；OpenAPI path 为 `packages/contracts/openapi/control-plane.yaml#/paths/~1extensions~1channel-providers`
7. `/api/extensions/tool-connectors` definition endpoint；OpenAPI path 为 `packages/contracts/openapi/control-plane.yaml#/paths/~1extensions~1tool-connectors`
8. channel admin DTO 改成 channel runtime profile + integration account reference
9. 可选固定常量密钥的 credential create / rotate / revoke / validate API；只有 descriptor 声明 credential endpoints 时 API 才是 credential invocation owner，负责调 extension credential endpoints 并写回 account 状态；不实现 refresh worker、provider-driven credential 写回或 logical operation 幂等恢复
10. Integration Account 可用性校验：生成新 runtime snapshot 前必须校验 `subjectType + subjectId` 匹配、`status = ENABLED`，且 `credential_status` 不在 `REVOKE_FAILED` / `REVOKED`；`NOT_CONFIGURED` / `VALIDATION_FAILED` / `ROTATION_REQUIRED` 只做风险提示
11. Tool Resource publish materialization（Slice 7）：发布 assistant release 时从 Tool Resource / Tool Operation 冻结 `connectorType`、可选 `accountSnapshot.accountId`、connector config、operation mapping、retry policy 和可选当前 `externalSecretRef`；`agent-runtime` 不回 API 拉取 credential 数据或账号状态；缺少 `externalSecretRef` 不阻塞发布
12. extension registry definition aggregation（统一通过 HTTP 调每条 registration 的 `/extension/manifest`，包括 core preset）
13. extension registration source loading：合并 operator yaml + core preset，preset baseUrl 来自 env
14. service-level manifest envelope loading
15. `registrationId` 与 manifest descriptor 白名单校验
16. registry descriptor id / definition digest consistency validation endpoint
17. provider definition endpoint response 使用 `ApiResponseChannelProviderDefinitionList` / `ChannelProviderDefinition`；暴露 `configSchema`、`defaultConfig`，assistant binding 不来自 provider manifest
18. connector definition endpoint response 使用 `ApiResponseToolConnectorDefinitionList` / `ToolConnectorDefinition`；响应字段固定包含 `configSchema`、`configUiSchema`、`operationMappingSchema`、`operationMappingUiSchema`
19. channel admin API 增加 external template binding CRUD；API / Web 不解释 provider-native template body
20. Channel Profile materialization（Slice 8）：channel admin API 到 `channel-gateway` internal DTO 的映射必须显式实现；Web-facing DTO 不返回 `externalSecretRef`，profile create / update 调 gateway 前由 API 物化可选 `accountSnapshot.accountId` 与可选 `accountSnapshot.externalSecretRef`；`channel-gateway` 不回 API 拉取 credential 数据或账号状态；缺少 `externalSecretRef` 不阻塞保存 / 启用
21. `packages/contracts/openapi/control-plane.yaml` 覆盖 Web-facing `/api/channel-admin/*`：profile CRUD、template binding CRUD、provider job config CRUD、manual job run 和 gateway error -> control-plane error mapping
22. manifest validation / contract tests 拒绝普通 user-editable config schema、channel / schedule defaults 中的
    `secret=true`、credential 明文、`externalSecretRef` 或可还原 secret material
23. manifest validation / contract tests 校验 UI schema sibling 字段、RFC 6901 JSON Pointer 引用、静态 options、conditional required 必须由 JSON Schema 表达，并识别 `visibilityCondition` 与当前 JSON Schema 分支 required 状态的明显冲突
24. normalized channel event contract tests 覆盖 `eventType` 字段矩阵，校验每类事件的
    `conversation` / `sender` / `message` / external id 必填、禁止和一致性规则
25. Integration Account control-plane API（list / get / create / update / archive / status / credential_status）落地到
    `packages/contracts/openapi/control-plane.yaml`，作为 Web 在 Channel / Tool 配置页判定 account 可用状态的
    唯一数据源；配置页用 account status 阻止保存 / 发布 / 启用，并把 `REVOKE_FAILED` / `REVOKED`
    作为 credential status 硬阻断；其他 credential status 只做风险提示（对齐 `web-configuration.md §9`）
26. Channel Profile 创建 / 更新 API 必须校验 `assistant_binding.assistantId` 存在、未归档、可发布且当前操作者可见；`scenarioId` 如填写也必须属于该 assistant 且可用。保存后不向 `channel-gateway` 同步 assistant / scenario invalidation；后续对话或 control-plane 操作再次引用已失效 assistant / scenario 时，由 API 返回结构化业务错误
27. API、`channel-gateway` 必须复用 `packages/extension-sdk-jvm` 的 HTTP client、DTO、`ExtensionError` 解析、
    canonical JSON、manifest validator；不得在 core 内独立实现协议层，与 `agent-runtime` 复用
    `packages/extension-sdk-python` 同等对待

### 6.5 Agent Runtime / Tool Runtime

1. `apps/agent-runtime/agentyard_agent_runtime/tool_connectors.py` 现有 connector 入口适配
2. `packages/extension-sdk-python` 提供协议 DTO / 类型、manifest envelope、canonical JSON、`ExtensionError` 解析、JSON Schema validator 和 pytest helpers
3. `apps/agent-runtime/agentyard_agent_runtime/extension_protocol*` 仅保留 runtime adapter 薄封装，不重复实现 SDK 已提供的协议能力
4. `apps/agent-runtime/agentyard_agent_runtime/descriptor_provider*` 内部 `DescriptorProvider`，聚合 built-in connector descriptor
5. `apps/agent-runtime/agentyard_agent_runtime/remote_connector*` remote adapter、HTTP client、timeout / retry / circuit breaker
6. `apps/agent-runtime/agentyard_agent_runtime/server*` 或等价 HTTP 模块暴露 `GET /extension/manifest`
7. `ToolConnectorRegistry`
8. `RemoteToolConnectorAdapter`
9. `RemoteToolInvokeRequest`
10. `ToolInvokeResponse`
11. `ExtensionError -> ToolExecutionFailure`
12. output 必须 JSON object
13. core runtime 执行 output schema 校验
14. connector retry policy 与 `ExtensionError.retryable` 合并
15. Python SDK manifest envelope 序列化、JSON Schema 校验、`ExtensionError` 解析、canonical JSON 实现必须跑与 Java SDK 同一份跨语言 fixtures，并在 `agent-runtime` CI 阻断不一致
16. `core-agent-runtime` preset registration 自加载路径（直接读 `DescriptorProvider`，不调自身 HTTP）
17. `agent-runtime` registry 只加载 `exposes.toolConnectorTypes` 非空的 registration；channel-only registration 不进入 manifest fetch、startup gate、runtime validation 或 descriptor digest comparison
18. `agent-runtime` 从 assistant release snapshot 构造 `RemoteToolInvokeRequest.externalSecretRef`、`config.connector`、`config.operationMapping` 和 output schema validation，不回 API 读取 Integration Account 或 connector definition

### 6.6 Web

1. `apps/web/src/types/channel.types.ts`
2. channel admin pages
3. provider definition driven forms
4. integration account management pages
5. Tool connector account selector
6. Channel profile 配置页
7. schema-driven credential form
8. schema-driven config / job form
9. operation mapping form
10. `SchemaDrivenForm` 使用 Ant Design Vue 控件和 Ajv 2020 validation，不引入重型 form generator
11. Channel profile 和需要账号的 Tool connector config 不再直接配置 credential，只选择 `integration_account.id`

### 6.7 Tests / Contract Tests

1. extension protocol schema tests
2. channel provider manifest tests
3. tool connector manifest tests
4. protocol self-check tests
5. Java SDK DTO / validation helper generation 或 contract consistency tests
6. Python SDK DTO / validation helper generation 或 contract consistency tests
7. extension implementation contract tests against local services
8. remote tool invoke contract tests
9. `ExtensionError` contract tests
10. normalized event ingestion tests
11. channel provider job `@Scheduled` scanner / Redis lock / idempotency tests；覆盖 DB claim 原子性、`scheduleConfig.enabled` 保存时投影为 `status`、manual run 运行中 409、DISABLED / PAUSED 不可手动触发、stale `RUNNING` recovery、丢失 lock 后迟到完成写回不覆盖已恢复终态
12. outbound delivery no-scheduled-retry failure-state tests
13. account credential create / rotate / revoke / validate tests；覆盖 credential endpoints 可选、固定常量密钥语义、account 级短时排他、用户手动重新提交、无 `Idempotency-Key` / `idempotencyKey`、无后台重试任务、无 logical operation 幂等恢复、无 token refresh / status sync 约束
14. registry descriptor id / definition digest consistency startup validation tests
15. existing Feishu webhook tests 改造成 gateway-native reference provider tests
16. external template binding resolution tests：assistantId + channelProfileId + CARD cardType/version 解析 external template ref，并快照到 delivery payload
17. outbound conversion owner tests：gateway-native 由 `channel-gateway` 转换，remote provider request 只传 canonical message block 和 resolved external template ref
18. `channel-gateway` active-active / rolling restart 语义 tests：provider job lock TTL、`RUNNING` job crash recovery、in-flight outbound failure state、无 leader 接管
19. `agent-runtime` 通过 Python SDK 跑 canonical JSON / manifest / `ExtensionError` fixtures，与 Java SDK 使用同一份协议样本
20. Channel Profile assistant binding tests：API 在 create / update 时拒绝不可见 / 不可发布 / 已归档 assistant；`channel-gateway` ingest 不直读 assistant / scenario 状态、不回 API 实时校验；后续对话或 control-plane 操作引用失效 assistant / scenario 时由 API 返回结构化业务错误
21. canonical JSON fixture / manifest digest contract tests：覆盖 `AgentYardCanonicalJson` 输入 profile、duplicate key 拒绝、safe integer 边界、float/decimal/exponent 拒绝、Unicode / escape、非 ASCII key 排序、大整数字符串化；fixture、算法或 digest 输入字段变更必须同时通过 protocol self-check、Java SDK、Python SDK 和 `agent-runtime` CI

#### 6.8 Docs / Deploy

1. extension protocol docs
2. enterprise extension repo template
3. static registration examples
4. channel provider runbook
5. tool connector runbook
6. secret management docs
7. deployment overlay examples，覆盖 core service 与 enterprise extension service 的同一 internal token Secret 投影
8. shared ConfigMap / Secret registration config examples；registration config 只投影给 core 三服务，internal token Secret 投影给 core 与 enterprise extension
9. deployment smoke check script / runbook，覆盖 enterprise extension 缺少 internal token file 的 auth failure 样例
10. shared extension registration file examples
11. extension health / metrics / audit docs
12. channel-gateway runtime topology runbook

## 7. Implementation Constraints

1. provider definition endpoint 必须作为 registry / definition slice 的一部分先落地，Channel Profile DTO 和 Web 表单只依赖 definition endpoint 暴露的 provider descriptor / schema
2. Feishu reference provider 当前继续留在原目录，不同步迁移到顶层 `extensions/`
3. 所有 descriptor 都通过 `/extension/manifest` envelope 暴露；runtime owner 自加载本服务 preset 可以走内部 `DescriptorProvider`，但不得另建第二套 descriptor 事实源
4. `channel-gateway` / `agent-runtime` 必须实现 `/extension/manifest` HTTP endpoint，与 enterprise extension 走同一套协议
5. core preset registration（`core-channel-gateway` / `core-agent-runtime`）由 core 自动注入，operator yaml 不允许声明或重写
6. API 拉取全部 registration 并做 full manifest validation；runtime owner 只拉取自身 descriptor 类型相关的 registration，`agent-runtime` 不依赖 channel-only extension，`channel-gateway` 不依赖 tool-only extension
7. canonical JSON 序列化算法固定采用 `AgentYardCanonicalJson` helper，输入限制为 descriptor / registration digest 场景的安全 profile，digest 固定为 `sha256:<lowercase-hex>`；跨语言（Java SDK、Python SDK 与 `agent-runtime` 对 Python SDK 的使用）必须对同一份输入产出 byte-for-byte 相同的 canonical bytes 和 digest；fixture、算法或 digest 输入字段变更必须同时通过 protocol self-check、Java SDK、Python SDK 和 `agent-runtime` CI
8. Credential lifecycle 是 descriptor 可选能力且只处理固定常量密钥；不使用 `Idempotency-Key` / `idempotencyKey`；任何 access token refresh / OAuth refresh token 轮换都属于 extension 私有实现，不进入 Core provider job、runtime owner 或 API 后台任务
9. Remote runtime invocation 只传 release snapshot / channel runtime profile 中已有的可选 `externalSecretRef`；Core 内部 `accountId` 只用于 snapshot 物化、校验和 internal credential resolver；不回 API 拉取 credential 数据或账号状态；缺少 credential 不作为 core runtime 协议错误
10. `channel-gateway` 多副本部署固定为 active-active；Postgres 是 channel runtime state 权威，Redis 只用于 provider job lock，outbound delivery 不进入调度模型
11. 外部模板不是 assistant message 字段，也不是 channel profile 的 template body；它是 assistant + channel profile + message type/subtype/version 到 provider-native external template ID 的 presentation binding
11. Assistant / session message 只保留现有 `SessionMessageInput.blocks` 语义结构；不得在 message block 中写入 `templateKey`、`externalTemplateId` 或 provider-native payload
12. Assistant binding 是平台固定 schema 的 `channel_profile` 运行配置，不由 provider manifest 声明 schema，不接受表达式、脚本或 provider-specific DSL
